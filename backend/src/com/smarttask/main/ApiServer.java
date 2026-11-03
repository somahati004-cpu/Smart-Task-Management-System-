package com.smarttask.main;

import com.smarttask.config.DBConnection;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

final class ApiServer {
    private static final int PASSWORD_ITERATIONS = 120_000;
    private static final int PASSWORD_KEY_LENGTH = 256;
    private static final int SESSION_LIFETIME_SECONDS = 86_400;
    private static final int MAX_REQUEST_BYTES = 65_536;
    private static final String SESSION_COOKIE = "SMARTTASK_SESSION";
    private static final Set<String> DEFAULT_DEV_ORIGINS = new HashSet<>(Arrays.asList(
            "http://127.0.0.1:5500",
            "http://localhost:5500"));
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Path webRoot;
    private final HttpServer server;

    ApiServer(int port) throws IOException, SQLException {
        initializeSchema();
        webRoot = resolveWebRoot();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool());
    }

    void start() {
        server.start();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/api/")) {
                if (!configureCors(exchange)) {
                    sendJson(exchange, 403, error("This origin is not allowed to access the API."));
                    return;
                }
                if ("OPTIONS".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }
                handleApi(exchange, path);
            } else {
                serveStatic(exchange, path);
            }
        } catch (ApiException exception) {
            sendJson(exchange, exception.status, error(exception.getMessage()));
        } catch (IllegalArgumentException exception) {
            sendJson(exchange, 400, error(exception.getMessage()));
        } catch (SQLException exception) {
            System.err.println("Database request failed: " + exception.getMessage());
            sendJson(exchange, 500, error("A database error occurred."));
        } catch (Exception exception) {
            System.err.println("Request failed: " + exception.getMessage());
            exception.printStackTrace(System.err);
            sendJson(exchange, 500, error("An unexpected server error occurred."));
        } finally {
            exchange.close();
        }
    }

    private static boolean configureCors(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null) {
            return true;
        }

        Set<String> allowedOrigins = new HashSet<>(DEFAULT_DEV_ORIGINS);
        String configuredOrigins = System.getenv("SMARTTASK_DEV_ORIGINS");
        if (configuredOrigins != null) {
            for (String configuredOrigin : configuredOrigins.split(",")) {
                if (!configuredOrigin.trim().isEmpty()) {
                    allowedOrigins.add(configuredOrigin.trim());
                }
            }
        }
        if (!allowedOrigins.contains(origin)) {
            return false;
        }

        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
        exchange.getResponseHeaders().set("Access-Control-Allow-Credentials", "true");
        exchange.getResponseHeaders().set(
                "Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        exchange.getResponseHeaders().set("Vary", "Origin");
        return true;
    }

    private void handleApi(HttpExchange exchange, String path) throws Exception {
        String method = exchange.getRequestMethod();
        if ("GET".equals(method) && "/api/health".equals(path)) {
            try (Connection connection = DBConnection.getConnection()) {
                sendJson(exchange, 200, mapOf(
                        "status", "ok",
                        "database", connection.getMetaData().getDatabaseProductName()));
            }
            return;
        }

        if ("POST".equals(method) && "/api/auth/register".equals(path)) {
            register(exchange);
            return;
        }
        if ("POST".equals(method) && "/api/auth/login".equals(path)) {
            login(exchange);
            return;
        }
        if ("POST".equals(method) && "/api/auth/logout".equals(path)) {
            logout(exchange);
            return;
        }
        if ("GET".equals(method) && "/api/auth/me".equals(path)) {
            Session session = requireSession(exchange);
            sendJson(exchange, 200, session.user);
            return;
        }

        if (path.equals("/api/wishes")) {
            Session session = requireSession(exchange);
            if ("GET".equals(method)) {
                listWishes(exchange, session);
            } else if ("POST".equals(method)) {
                createWish(exchange, session);
            } else {
                sendJson(exchange, 405, error("Method not allowed."));
            }
            return;
        }

        if (path.startsWith("/api/wishes/")) {
            Session session = requireSession(exchange);
            String wishId = path.substring("/api/wishes/".length());
            if (!isUuid(wishId)) {
                throw new ApiException(404, "Wish not found.");
            }
            if ("PUT".equals(method)) {
                updateWish(exchange, session, wishId);
            } else if ("DELETE".equals(method)) {
                deleteWish(exchange, session, wishId);
            } else {
                sendJson(exchange, 405, error("Method not allowed."));
            }
            return;
        }

        sendJson(exchange, 404, error("API endpoint not found."));
    }

    private void register(HttpExchange exchange) throws Exception {
        Map<String, Object> input = requestObject(exchange);
        String fullName = requiredString(input, "fullName", 120);
        String email = requiredString(input, "email", 254).toLowerCase(Locale.ROOT);
        String username = requiredString(input, "username", 30).toLowerCase(Locale.ROOT);
        String password = requiredPassword(input);

        if (!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new ApiException(400, "Enter a valid email address.");
        }
        if (!username.matches("^[A-Za-z0-9_]{3,30}$")) {
            throw new ApiException(
                    400, "Username must be 3-30 characters and use letters, numbers, or underscores.");
        }
        if (password.length() < 8) {
            throw new ApiException(400, "Password must be at least 8 characters.");
        }

        String userId = UUID.randomUUID().toString();
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        String encodedSalt = Base64.getEncoder().encodeToString(salt);
        String encodedHash = hashPassword(password.toCharArray(), salt);

        try (Connection connection = DBConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO SMARTTASK_USERS "
                             + "(ID, FULL_NAME, EMAIL, USERNAME, PASSWORD_HASH, PASSWORD_SALT) "
                             + "VALUES (?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, userId);
            statement.setString(2, fullName);
            statement.setString(3, email);
            statement.setString(4, username);
            statement.setString(5, encodedHash);
            statement.setString(6, encodedSalt);
            statement.executeUpdate();
        } catch (SQLException exception) {
            if (exception.getErrorCode() == 1) {
                throw new ApiException(409, "That email or username is already registered.");
            }
            throw exception;
        }

        Map<String, Object> user = mapOf(
                "id", userId,
                "fullName", fullName,
                "username", username,
                "email", email);
        createSession(exchange, user);
        sendJson(exchange, 201, user);
    }

    private void login(HttpExchange exchange) throws Exception {
        Map<String, Object> input = requestObject(exchange);
        String identifier = requiredString(input, "username", 254).toLowerCase(Locale.ROOT);
        String password = requiredPassword(input);

        try (Connection connection = DBConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT ID, FULL_NAME, USERNAME, EMAIL, PASSWORD_HASH, PASSWORD_SALT "
                             + "FROM SMARTTASK_USERS WHERE LOWER(USERNAME) = ? OR LOWER(EMAIL) = ?")) {
            statement.setString(1, identifier);
            statement.setString(2, identifier);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new ApiException(401, "Incorrect username/email or password.");
                }
                byte[] salt = Base64.getDecoder().decode(result.getString("PASSWORD_SALT"));
                byte[] expected = Base64.getDecoder().decode(result.getString("PASSWORD_HASH"));
                byte[] actual = Base64.getDecoder().decode(
                        hashPassword(password.toCharArray(), salt));
                if (!MessageDigest.isEqual(expected, actual)) {
                    throw new ApiException(401, "Incorrect username/email or password.");
                }
                Map<String, Object> user = mapOf(
                        "id", result.getString("ID"),
                        "fullName", result.getString("FULL_NAME"),
                        "username", result.getString("USERNAME"),
                        "email", result.getString("EMAIL"));
                createSession(exchange, user);
                sendJson(exchange, 200, user);
            }
        }
    }

    private void logout(HttpExchange exchange) throws IOException {
        String token = sessionToken(exchange);
        if (token != null) {
            sessions.remove(token);
        }
        exchange.getResponseHeaders().add(
                "Set-Cookie", SESSION_COOKIE + "=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0");
        sendJson(exchange, 200, mapOf("message", "Logged out."));
    }

    private void listWishes(HttpExchange exchange, Session session) throws SQLException, IOException {
        List<Map<String, Object>> wishes = new ArrayList<>();
        try (Connection connection = DBConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT ID, TITLE, DESCRIPTION, IS_READ, CREATED_AT "
                             + "FROM SMARTTASK_WISHES WHERE USER_ID = ? ORDER BY CREATED_AT DESC")) {
            statement.setString(1, (String) session.user.get("id"));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    wishes.add(wishFrom(result));
                }
            }
        }
        sendJson(exchange, 200, wishes);
    }

    private void createWish(HttpExchange exchange, Session session) throws Exception {
        Map<String, Object> input = requestObject(exchange);
        String title = requiredString(input, "title", 120);
        String description = optionalString(input, "description", 1000);
        String id = UUID.randomUUID().toString();
        try (Connection connection = DBConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO SMARTTASK_WISHES (ID, USER_ID, TITLE, DESCRIPTION, IS_READ) "
                             + "VALUES (?, ?, ?, ?, 0)")) {
            statement.setString(1, id);
            statement.setString(2, (String) session.user.get("id"));
            statement.setString(3, title);
            statement.setString(4, description);
            statement.executeUpdate();
        }
        sendJson(exchange, 201, mapOf(
                "id", id,
                "title", title,
                "description", description,
                "isRead", false,
                "createdAt", Instant.now().toString()));
    }

    private void updateWish(HttpExchange exchange, Session session, String id)
            throws Exception {
        Map<String, Object> input = requestObject(exchange);
        String userId = (String) session.user.get("id");
        Map<String, Object> existing;
        try (Connection connection = DBConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT ID, TITLE, DESCRIPTION, IS_READ, CREATED_AT "
                             + "FROM SMARTTASK_WISHES WHERE ID = ? AND USER_ID = ?")) {
            statement.setString(1, id);
            statement.setString(2, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new ApiException(404, "Wish not found.");
                }
                existing = wishFrom(result);
            }
        }

        String title = input.containsKey("title")
                ? requiredString(input, "title", 120)
                : (String) existing.get("title");
        String description = input.containsKey("description")
                ? optionalString(input, "description", 1000)
                : (String) existing.get("description");
        boolean isRead = input.containsKey("isRead")
                ? requiredBoolean(input, "isRead")
                : (Boolean) existing.get("isRead");

        try (Connection connection = DBConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE SMARTTASK_WISHES SET TITLE = ?, DESCRIPTION = ?, IS_READ = ?, "
                             + "UPDATED_AT = CURRENT_TIMESTAMP WHERE ID = ? AND USER_ID = ?")) {
            statement.setString(1, title);
            statement.setString(2, description);
            statement.setInt(3, isRead ? 1 : 0);
            statement.setString(4, id);
            statement.setString(5, userId);
            if (statement.executeUpdate() == 0) {
                throw new ApiException(404, "Wish not found.");
            }
        }
        existing.put("title", title);
        existing.put("description", description);
        existing.put("isRead", isRead);
        sendJson(exchange, 200, existing);
    }

    private void deleteWish(HttpExchange exchange, Session session, String id)
            throws SQLException, IOException {
        try (Connection connection = DBConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM SMARTTASK_WISHES WHERE ID = ? AND USER_ID = ?")) {
            statement.setString(1, id);
            statement.setString(2, (String) session.user.get("id"));
            if (statement.executeUpdate() == 0) {
                throw new ApiException(404, "Wish not found.");
            }
        }
        sendJson(exchange, 200, mapOf("message", "Wish deleted."));
    }

    private Session requireSession(HttpExchange exchange) {
        String token = sessionToken(exchange);
        Session session = token == null ? null : sessions.get(token);
        if (session == null || session.expiresAt < System.currentTimeMillis()) {
            if (token != null) {
                sessions.remove(token);
            }
            throw new ApiException(401, "Please log in to continue.");
        }
        return session;
    }

    private void createSession(HttpExchange exchange, Map<String, Object> user)
            throws IOException {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(entry -> entry.getValue().expiresAt < now);
        byte[] tokenBytes = new byte[32];
        RANDOM.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        sessions.put(token, new Session(
                Collections.unmodifiableMap(new LinkedHashMap<>(user)),
                now + SESSION_LIFETIME_SECONDS * 1000L));
        exchange.getResponseHeaders().add(
                "Set-Cookie",
                SESSION_COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Lax; Max-Age="
                        + SESSION_LIFETIME_SECONDS);
    }

    private static String sessionToken(HttpExchange exchange) {
        List<String> cookies = exchange.getRequestHeaders().get("Cookie");
        if (cookies == null) {
            return null;
        }
        for (String cookieHeader : cookies) {
            for (String cookie : cookieHeader.split(";")) {
                String[] pair = cookie.trim().split("=", 2);
                if (pair.length == 2 && SESSION_COOKIE.equals(pair[0])) {
                    return pair[1];
                }
            }
        }
        return null;
    }

    private static Map<String, Object> requestObject(HttpExchange exchange)
            throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase().startsWith("application/json")) {
            throw new ApiException(415, "Content-Type must be application/json.");
        }
        byte[] body;
        try (InputStream input = exchange.getRequestBody()) {
            body = input.readNBytes(MAX_REQUEST_BYTES + 1);
        }
        if (body.length > MAX_REQUEST_BYTES) {
            throw new ApiException(413, "Request body is too large.");
        }
        return Json.object(Json.parse(new String(body, StandardCharsets.UTF_8)));
    }

    private static String requiredString(Map<String, Object> input, String key, int maxLength) {
        Object value = input.get(key);
        if (!(value instanceof String)) {
            throw new ApiException(400, key + " is required.");
        }
        String string = ((String) value).trim();
        if (string.isEmpty() || string.length() > maxLength) {
            throw new ApiException(400, key + " must be between 1 and " + maxLength + " characters.");
        }
        return string;
    }

    private static String requiredPassword(Map<String, Object> input) {
        Object value = input.get("password");
        if (!(value instanceof String)) {
            throw new ApiException(400, "password is required.");
        }
        String password = (String) value;
        if (password.isEmpty() || password.length() > 256) {
            throw new ApiException(400, "password must be between 1 and 256 characters.");
        }
        return password;
    }

    private static String optionalString(Map<String, Object> input, String key, int maxLength) {
        Object value = input.get(key);
        if (value == null) {
            return "";
        }
        if (!(value instanceof String)) {
            throw new ApiException(400, key + " must be text.");
        }
        String string = ((String) value).trim();
        if (string.length() > maxLength) {
            throw new ApiException(400, key + " must be no more than " + maxLength + " characters.");
        }
        return string;
    }

    private static boolean requiredBoolean(Map<String, Object> input, String key) {
        Object value = input.get(key);
        if (!(value instanceof Boolean)) {
            throw new ApiException(400, key + " must be true or false.");
        }
        return (Boolean) value;
    }

    private static String hashPassword(char[] password, byte[] salt) throws Exception {
        PBEKeySpec specification = new PBEKeySpec(
                password, salt, PASSWORD_ITERATIONS, PASSWORD_KEY_LENGTH);
        try {
            byte[] hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(specification)
                    .getEncoded();
            return Base64.getEncoder().encodeToString(hash);
        } finally {
            specification.clearPassword();
            java.util.Arrays.fill(password, '\0');
        }
    }

    private static void initializeSchema() throws SQLException {
        try (Connection connection = DBConnection.getConnection()) {
            createTable(connection,
                    "CREATE TABLE SMARTTASK_USERS ("
                            + "ID VARCHAR2(36) PRIMARY KEY, "
                            + "FULL_NAME VARCHAR2(120) NOT NULL, "
                            + "EMAIL VARCHAR2(254) NOT NULL UNIQUE, "
                            + "USERNAME VARCHAR2(30) NOT NULL UNIQUE, "
                            + "PASSWORD_HASH VARCHAR2(128) NOT NULL, "
                            + "PASSWORD_SALT VARCHAR2(64) NOT NULL, "
                            + "CREATED_AT TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL)");
            createTable(connection,
                    "CREATE TABLE SMARTTASK_WISHES ("
                            + "ID VARCHAR2(36) PRIMARY KEY, "
                            + "USER_ID VARCHAR2(36) NOT NULL, "
                            + "TITLE VARCHAR2(120) NOT NULL, "
                            + "DESCRIPTION VARCHAR2(1000), "
                            + "IS_READ NUMBER(1) DEFAULT 0 NOT NULL CHECK (IS_READ IN (0, 1)), "
                            + "CREATED_AT TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL, "
                            + "UPDATED_AT TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL, "
                            + "CONSTRAINT FK_SMARTTASK_WISH_USER FOREIGN KEY (USER_ID) "
                            + "REFERENCES SMARTTASK_USERS(ID) ON DELETE CASCADE)");
        }
    }

    private static void createTable(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.execute();
        } catch (SQLException exception) {
            if (exception.getErrorCode() != 955) {
                throw exception;
            }
        }
    }

    private static Map<String, Object> wishFrom(ResultSet result) throws SQLException {
        Timestamp createdAt = result.getTimestamp("CREATED_AT");
        return mapOf(
                "id", result.getString("ID"),
                "title", result.getString("TITLE"),
                "description", result.getString("DESCRIPTION"),
                "isRead", result.getInt("IS_READ") == 1,
                "createdAt", createdAt == null ? null : createdAt.toInstant().toString());
    }

    private void serveStatic(HttpExchange exchange, String requestPath)
            throws IOException {
        if (!"GET".equals(exchange.getRequestMethod()) && !"HEAD".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, error("Method not allowed."));
            return;
        }
        String relativePath = "/".equals(requestPath) ? "index.html" : requestPath.substring(1);
        Path file = webRoot.resolve(relativePath).normalize();
        if (!file.startsWith(webRoot) || !Files.isRegularFile(file)) {
            sendJson(exchange, 404, error("Page not found."));
            return;
        }
        String contentType = contentType(file);
        byte[] content = Files.readAllBytes(file);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        if ("HEAD".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(200, -1);
        } else {
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
        }
    }

    private static String contentType(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (name.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (name.endsWith(".js")) {
            return "text/javascript; charset=utf-8";
        }
        if (name.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        return "application/octet-stream";
    }

    private static Path resolveWebRoot() {
        String configured = System.getenv("SMARTTASK_WEB_DIR");
        if (configured != null && !configured.trim().isEmpty()) {
            Path root = Paths.get(configured.trim()).toAbsolutePath().normalize();
            if (Files.isDirectory(root)) {
                return root;
            }
            throw new IllegalStateException("SMARTTASK_WEB_DIR is not a directory: " + root);
        }
        Path current = Paths.get("").toAbsolutePath().normalize();
        Path[] candidates = {current.resolve("web"), current.resolve("..").resolve("web").normalize()};
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "Could not locate the web folder. Set SMARTTASK_WEB_DIR to its path.");
    }

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static Map<String, Object> error(String message) {
        return mapOf("error", message);
    }

    private static Map<String, Object> mapOf(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put((String) entries[index], entries[index + 1]);
        }
        return result;
    }

    private static void sendJson(HttpExchange exchange, int status, Object body)
            throws IOException {
        byte[] bytes = Json.stringify(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static final class Session {
        private final Map<String, Object> user;
        private final long expiresAt;

        private Session(Map<String, Object> user, long expiresAt) {
            this.user = user;
            this.expiresAt = expiresAt;
        }
    }

    private static final class ApiException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final int status;

        private ApiException(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
