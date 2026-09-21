package com.initialneko.cdp;

import com.alibaba.fastjson.JSONObject;

import java.util.Locale;
import java.util.Map;

public final class PageRequest {
    private final String url;
    private final String method;
    private final JSONObject headers;
    private final String body;
    private final String credentials;
    private final String redirect;

    private PageRequest(Builder builder) {
        if (builder.url == null || builder.url.trim().isEmpty()) {
            throw new IllegalArgumentException("url cannot be empty");
        }
        this.url = builder.url;
        this.method = normalizeMethod(builder.method);
        this.headers = copyHeaders(builder.headers);
        this.body = builder.body;
        this.credentials = builder.credentials == null
                ? "include"
                : builder.credentials;
        this.redirect = builder.redirect == null
                ? "follow"
                : builder.redirect;
    }

    public static Builder builder(String url) {
        return new Builder(url);
    }

    public static PageRequest get(String url) {
        return builder(url).method("GET").build();
    }

    public static PageRequest postJson(String url, String json) {
        return builder(url)
                .method("POST")
                .header("Content-Type", "application/json")
                .body(json)
                .build();
    }

    public static Builder replay(CapturedExchange exchange) {
        if (exchange == null) {
            throw new IllegalArgumentException("exchange cannot be null");
        }

        Builder builder = builder(exchange.getUrl())
                .method(exchange.getMethod())
                .body(exchange.getRequestBody());

        JSONObject source = exchange.getRequestHeaders();
        if (source != null) {
            for (Map.Entry<String, Object> entry : source.entrySet()) {
                String name = entry.getKey();
                if (!isForbiddenReplayHeader(name)) {
                    builder.header(
                            name,
                            entry.getValue() == null
                                    ? ""
                                    : String.valueOf(entry.getValue()));
                }
            }
        }

        return builder;
    }

    JSONObject toJson() {
        JSONObject json = new JSONObject(true);
        json.put("url", url);
        json.put("method", method);
        json.put("headers", copyHeaders(headers));
        json.put("body", body);
        json.put("credentials", credentials);
        json.put("redirect", redirect);
        return json;
    }

    public String getUrl() { return url; }
    public String getMethod() { return method; }
    public JSONObject getHeaders() { return copyHeaders(headers); }
    public String getBody() { return body; }
    public String getCredentials() { return credentials; }
    public String getRedirect() { return redirect; }

    private static String normalizeMethod(String method) {
        if (method == null || method.trim().isEmpty()) {
            return "GET";
        }
        return method.trim().toUpperCase(Locale.ROOT);
    }

    private static JSONObject copyHeaders(JSONObject source) {
        JSONObject out = new JSONObject(true);
        if (source == null) {
            return out;
        }
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            out.put(entry.getKey(), entry.getValue());
        }
        return out;
    }

    private static boolean isForbiddenReplayHeader(String name) {
        if (name == null) {
            return true;
        }

        String lower = name.trim().toLowerCase(Locale.ROOT);
        if (lower.isEmpty()
                || lower.startsWith(":")
                || lower.startsWith("sec-")
                || lower.startsWith("proxy-")) {
            return true;
        }

        return "accept-encoding".equals(lower)
                || "connection".equals(lower)
                || "content-length".equals(lower)
                || "cookie".equals(lower)
                || "cookie2".equals(lower)
                || "host".equals(lower)
                || "keep-alive".equals(lower)
                || "origin".equals(lower)
                || "referer".equals(lower)
                || "te".equals(lower)
                || "trailer".equals(lower)
                || "transfer-encoding".equals(lower)
                || "upgrade".equals(lower)
                || "user-agent".equals(lower)
                || "via".equals(lower);
    }

    public static final class Builder {
        private final String url;
        private String method = "GET";
        private final JSONObject headers = new JSONObject(true);
        private String body;
        private String credentials = "include";
        private String redirect = "follow";

        private Builder(String url) {
            this.url = url;
        }

        public Builder method(String method) {
            this.method = method;
            return this;
        }

        public Builder header(String name, String value) {
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("header name cannot be empty");
            }
            headers.put(name, value == null ? "" : value);
            return this;
        }

        public Builder headers(JSONObject values) {
            if (values != null) {
                for (Map.Entry<String, Object> entry : values.entrySet()) {
                    header(
                            entry.getKey(),
                            entry.getValue() == null
                                    ? ""
                                    : String.valueOf(entry.getValue()));
                }
            }
            return this;
        }

        public Builder body(String body) {
            this.body = body;
            return this;
        }

        public Builder credentials(String credentials) {
            this.credentials = credentials;
            return this;
        }

        public Builder redirect(String redirect) {
            this.redirect = redirect;
            return this;
        }

        public PageRequest build() {
            return new PageRequest(this);
        }
    }
}
