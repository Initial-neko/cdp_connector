package com.initialneko.cdp;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONPath;
import com.alibaba.fastjson.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class PageResponse {
    private final int status;
    private final String statusText;
    private final String url;
    private final boolean redirected;
    private final JSONObject headers;
    private final String body;

    private PageResponse(
            int status,
            String statusText,
            String url,
            boolean redirected,
            JSONObject headers,
            String body) {
        this.status = status;
        this.statusText = statusText;
        this.url = url;
        this.redirected = redirected;
        this.headers = headers == null
                ? new JSONObject(true)
                : headers;
        this.body = body;
    }

    static PageResponse fromJson(JSONObject json) {
        if (json == null) {
            throw new CdpException("Page request returned no response");
        }
        return new PageResponse(
                json.getIntValue("status"),
                json.getString("statusText"),
                json.getString("url"),
                json.getBooleanValue("redirected"),
                json.getJSONObject("headers"),
                json.getString("body"));
    }

    public int getStatus() { return status; }
    public String getStatusText() { return statusText; }
    public String getUrl() { return url; }
    public boolean isRedirected() { return redirected; }
    public JSONObject getHeaders() { return headers; }
    public String getBody() { return body; }

    public boolean isOk() {
        return status >= 200 && status < 300;
    }

    public String header(String name) {
        if (name == null) {
            return null;
        }
        for (Map.Entry<String, Object> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue() == null
                        ? null
                        : String.valueOf(entry.getValue());
            }
        }
        return null;
    }

    public boolean isHtml() {
        String contentType = header("content-type");
        return contentType != null
                && contentType.toLowerCase().contains("text/html");
    }

    public Object jsonValue() {
        if (body == null || body.trim().isEmpty()) {
            return null;
        }
        try {
            return JSON.parse(body);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public JSONObject json() {
        Object value = jsonValue();
        return value instanceof JSONObject
                ? (JSONObject) value
                : null;
    }

    public Object jsonPath(String path) {
        Object value = jsonValue();
        if (value == null) {
            return null;
        }
        try {
            return JSONPath.eval(value, path);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public void writeBody(Path path) {
        try {
            byte[] bytes = body == null
                    ? new byte[0]
                    : body.getBytes(StandardCharsets.UTF_8);
            Files.write(path, bytes);
        } catch (IOException e) {
            throw new CdpException(
                    "Failed to write response body: " + path,
                    e);
        }
    }

    public String summary() {
        return status + " " + (statusText == null ? "" : statusText)
                + " " + (url == null ? "" : url);
    }
}
