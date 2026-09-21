package com.initialneko.cdp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;

final class TestPageServer implements AutoCloseable {
    private static final String SESSION_COOKIE =
            "CDP_TEST_SESSION=logged-in";

    private final HttpServer server;

    TestPageServer(int port) throws IOException {
        server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", port),
                0);
        server.setExecutor(
                Executors.newCachedThreadPool());

        server.createContext(
                "/",
                new ResourceHandler(
                        "/test-site/index.html",
                        "text/html; charset=UTF-8"));

        server.createContext("/api/json", exchange -> {
            byte[] body =
                    "{\"ok\":true,\"type\":\"get-json\"}"
                            .getBytes(StandardCharsets.UTF_8);
            send(
                    exchange,
                    200,
                    "application/json; charset=UTF-8",
                    body);
        });

        server.createContext("/api/echo", exchange -> {
            String requestBody =
                    read(exchange.getRequestBody());
            String escaped = escapeJson(requestBody);

            byte[] body = (
                    "{\"ok\":true,\"requestBody\":\""
                            + escaped
                            + "\"}")
                    .getBytes(StandardCharsets.UTF_8);

            send(
                    exchange,
                    200,
                    "application/json; charset=UTF-8",
                    body);
        });

        server.createContext("/api/authenticated", exchange -> {
            if (!requireSession(exchange)) {
                return;
            }

            send(
                    exchange,
                    200,
                    "application/json; charset=UTF-8",
                    "{\"ok\":true,\"authenticated\":true}"
                            .getBytes(StandardCharsets.UTF_8));
        });

        server.createContext("/api/step1", exchange -> {
            if (!requireSession(exchange)) {
                return;
            }

            String requestBody = read(exchange.getRequestBody());
            String body = "{\"ok\":true,"
                    + "\"nextId\":\"A-100\","
                    + "\"requestBody\":\""
                    + escapeJson(requestBody)
                    + "\"}";

            send(
                    exchange,
                    200,
                    "application/json; charset=UTF-8",
                    body.getBytes(StandardCharsets.UTF_8));
        });

        server.createContext("/api/end", exchange -> {
            if (!requireSession(exchange)) {
                return;
            }

            String requestBody = read(exchange.getRequestBody());
            String body = "{\"ok\":true,"
                    + "\"ended\":true,"
                    + "\"requestBody\":\""
                    + escapeJson(requestBody)
                    + "\"}";

            send(
                    exchange,
                    200,
                    "application/json; charset=UTF-8",
                    body.getBytes(StandardCharsets.UTF_8));
        });

        server.createContext("/api/report-html", exchange -> {
            if (!requireSession(exchange)) {
                return;
            }

            String body =
                    "<article id=\"analysis-html\">"
                            + "<h2>analysis report</h2>"
                            + "<p>generated-from-authenticated-api</p>"
                            + "</article>";

            send(
                    exchange,
                    200,
                    "text/html; charset=UTF-8",
                    body.getBytes(StandardCharsets.UTF_8));
        });

        server.createContext("/fragment", exchange -> {
            byte[] body =
                    "<div id=\"server-fragment\">fragment-loaded</div>"
                            .getBytes(StandardCharsets.UTF_8);
            send(
                    exchange,
                    200,
                    "text/html; charset=UTF-8",
                    body);
        });

        server.createContext("/api/slow", exchange -> {
            try {
                Thread.sleep(250L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            byte[] body =
                    "{\"ok\":true,\"slow\":true}"
                            .getBytes(StandardCharsets.UTF_8);

            send(
                    exchange,
                    200,
                    "application/json; charset=UTF-8",
                    body);
        });

        server.createContext("/api/error", exchange -> {
            byte[] body =
                    "{\"ok\":false,\"error\":\"intentional-test-error\"}"
                            .getBytes(StandardCharsets.UTF_8);

            send(
                    exchange,
                    500,
                    "application/json; charset=UTF-8",
                    body);
        });
    }

    void start() {
        server.start();
    }

    private static boolean requireSession(
            HttpExchange exchange) throws IOException {
        List<String> cookies =
                exchange.getRequestHeaders().get("Cookie");

        if (cookies != null) {
            for (String cookie : cookies) {
                if (cookie != null
                        && cookie.contains(SESSION_COOKIE)) {
                    return true;
                }
            }
        }

        send(
                exchange,
                401,
                "application/json; charset=UTF-8",
                "{\"ok\":false,\"error\":\"not-authenticated\"}"
                        .getBytes(StandardCharsets.UTF_8));
        return false;
    }

    private static void send(
            HttpExchange exchange,
            int status,
            String contentType,
            byte[] body) throws IOException {
        exchange.getResponseHeaders().set(
                "Content-Type",
                contentType);
        exchange.getResponseHeaders().set(
                "Cache-Control",
                "no-store");

        exchange.sendResponseHeaders(
                status,
                body.length);

        try (OutputStream out =
                     exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static String read(
            InputStream in) throws IOException {
        ByteArrayOutputStream out =
                new ByteArrayOutputStream();

        byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) >= 0) {
            out.write(buffer, 0, n);
        }

        return new String(
                out.toByteArray(),
                StandardCharsets.UTF_8);
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private static final class ResourceHandler
            implements HttpHandler {
        private final String resource;
        private final String contentType;

        private ResourceHandler(
                String resource,
                String contentType) {
            this.resource = resource;
            this.contentType = contentType;
        }

        @Override
        public void handle(
                HttpExchange exchange)
                throws IOException {
            InputStream in =
                    TestPageServer.class
                            .getResourceAsStream(resource);

            if (in == null) {
                send(
                        exchange,
                        404,
                        "text/plain; charset=UTF-8",
                        ("Missing resource: " + resource)
                                .getBytes(
                                        StandardCharsets.UTF_8));
                return;
            }

            ByteArrayOutputStream out =
                    new ByteArrayOutputStream();

            byte[] buffer = new byte[4096];
            int n;

            try {
                while ((n = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, n);
                }
            } finally {
                in.close();
            }

            exchange.getResponseHeaders().add(
                    "Set-Cookie",
                    SESSION_COOKIE
                            + "; Path=/; HttpOnly; SameSite=Lax");

            send(
                    exchange,
                    200,
                    contentType,
                    out.toByteArray());
        }
    }
}
