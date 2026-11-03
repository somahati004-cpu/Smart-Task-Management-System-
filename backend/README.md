# SmartTask backend

The backend is a Java HTTP server that serves the `web` frontend and provides
JSON endpoints for account authentication and per-user wish management. Wish
data and accounts are stored in Oracle; tables are created automatically when
the server starts.

## Requirements

- JDK 11 or later
- Oracle Database reachable by the JDBC URL
- PowerShell on Windows

The default JDBC URL is `jdbc:oracle:thin:@//localhost:1522/orcl`. Configure
the database connection before starting the application; database credentials
are intentionally read from environment variables:

```powershell
$env:SMARTTASK_DB_USER = "your_database_user"
$env:SMARTTASK_DB_PASSWORD = "your_database_password"
# Optional:
$env:SMARTTASK_DB_URL = "jdbc:oracle:thin:@//localhost:1522/orcl"
$env:SMARTTASK_PORT = "8080"
.\backend\run.ps1
```

Open [http://127.0.0.1:8080](http://127.0.0.1:8080). The server binds to
localhost and serves the frontend and API from the same origin. If you preview
the frontend with VS Code Live Server on port 5500, the frontend automatically
targets the backend on port 8080; the backend allows the loopback Live Server
origins by default. Override the comma-separated development origin list with
`SMARTTASK_DEV_ORIGINS` if needed. Set `SMARTTASK_WEB_DIR` if the `web`
directory is not next to the project root.

## API

All request and response bodies use JSON. Authenticated requests use the
HttpOnly `SMARTTASK_SESSION` cookie set by login or registration.

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/health` | Check server and database availability |
| `POST` | `/api/auth/register` | Register with `fullName`, `email`, `username`, `password` |
| `POST` | `/api/auth/login` | Login with `username` (username or email) and `password` |
| `GET` | `/api/auth/me` | Get the current account |
| `POST` | `/api/auth/logout` | End the current session |
| `GET` | `/api/wishes` | List the signed-in user's wishes |
| `POST` | `/api/wishes` | Create with `title` and optional `description` |
| `PUT` | `/api/wishes/{id}` | Update `title`, `description`, and/or `isRead` |
| `DELETE` | `/api/wishes/{id}` | Delete a wish |

Passwords are stored as PBKDF2-HMAC-SHA256 hashes with individual random
salts. Sessions are held in memory and expire after 24 hours; restarting the
server ends all active sessions.

## Checks

Compile and start with `.\backend\run.ps1`. To run the JSON parser checks:

```powershell
javac -d backend\out backend\src\com\smarttask\main\Json.java backend\test\com\smarttask\main\JsonTest.java
java -cp backend\out com.smarttask.main.JsonTest
```
