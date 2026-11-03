
package com.smarttask.main;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        int port = getPort();
        ApiServer server = new ApiServer(port);
        server.start();
        System.out.println("SmartTask backend is running at http://localhost:" + port);
    }

    private static int getPort() {
        String configured = System.getenv("SMARTTASK_PORT");
        if (configured == null || configured.trim().isEmpty()) {
            return 8080;
        }
        try {
            int port = Integer.parseInt(configured.trim());
            if (port < 1 || port > 65535) {
                throw new NumberFormatException();
            }
            return port;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("SMARTTASK_PORT must be between 1 and 65535.");
        }
    }
}
