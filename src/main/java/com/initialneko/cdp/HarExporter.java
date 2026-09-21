package com.initialneko.cdp;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.net.URI;
import java.net.URLDecoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.TimeZone;

final class HarExporter {
    private HarExporter() {
    }

    static JSONObject toHar(CaptureSession session) {
        JSONObject root = new JSONObject();
        JSONObject log = new JSONObject();
        root.put("log", log);

        log.put("version", "1.2");

        JSONObject creator = new JSONObject();
        creator.put("name", "cdp-connector");
        creator.put("version", "0.1.0");
        log.put("creator", creator);

        JSONArray pages = new JSONArray();
        JSONObject page = new JSONObject();
        page.put(
                "startedDateTime",
                iso(session.getStartedAtMillis()));
        page.put("id", "page_1");
        page.put(
                "title",
                session.getStartTitle() == null
                        ? ""
                        : session.getStartTitle());

        JSONObject pageTimings = new JSONObject();
        pageTimings.put("onContentLoad", -1);
        pageTimings.put("onLoad", -1);
        page.put("pageTimings", pageTimings);
        pages.add(page);
        log.put("pages", pages);

        JSONArray entries = new JSONArray();
        for (CapturedExchange exchange : session.getExchanges()) {
            entries.add(toEntry(exchange));
        }
        log.put("entries", entries);

        JSONObject snapshots = new JSONObject();
        snapshots.put("startUrl", session.getStartUrl());
        snapshots.put("startHtml", session.getStartHtml());
        snapshots.put("endUrl", session.getEndUrl());
        snapshots.put("endHtml", session.getEndHtml());
        root.put("_pageSnapshots", snapshots);

        return root;
    }

    private static JSONObject toEntry(CapturedExchange x) {
        JSONObject entry = new JSONObject();

        long startedMillis = x.wallTimeSeconds > 0
                ? (long) (x.wallTimeSeconds * 1000L)
                : System.currentTimeMillis();

        entry.put("startedDateTime", iso(startedMillis));
        entry.put("time", elapsedMillis(x));
        entry.put("pageref", "page_1");

        JSONObject request = new JSONObject();
        request.put("method", nullToEmpty(x.method));
        request.put("url", nullToEmpty(x.url));
        request.put("httpVersion", httpVersion(x.protocol));
        request.put("headers", headers(x.requestHeaders));
        request.put("queryString", queryString(x.url));
        request.put("cookies", new JSONArray());
        request.put("headersSize", -1);
        request.put(
                "bodySize",
                x.requestBody == null ? 0 : x.requestBody.length());

        if (x.requestBody != null) {
            JSONObject postData = new JSONObject();
            postData.put(
                    "mimeType",
                    headerValue(x.requestHeaders, "content-type"));
            postData.put("text", x.requestBody);
            if (x.requestBodyTruncated) {
                postData.put("_truncated", true);
            }
            request.put("postData", postData);
        }
        entry.put("request", request);

        JSONObject response = new JSONObject();
        response.put("status", x.status);
        response.put("statusText", nullToEmpty(x.statusText));
        response.put("httpVersion", httpVersion(x.protocol));
        response.put("headers", headers(x.responseHeaders));
        response.put("cookies", new JSONArray());
        response.put(
                "redirectURL",
                headerValue(x.responseHeaders, "location"));
        response.put("headersSize", -1);
        response.put("bodySize", x.encodedDataLength);

        JSONObject content = new JSONObject();
        content.put(
                "size",
                x.responseBody == null ? 0 : x.responseBody.length());
        content.put("mimeType", nullToEmpty(x.mimeType));
        if (x.responseBody != null) {
            content.put("text", x.responseBody);
            if (x.responseBodyBase64) {
                content.put("encoding", "base64");
            }
            if (x.responseBodyTruncated) {
                content.put("_truncated", true);
            }
        }
        response.put("content", content);
        entry.put("response", response);

        entry.put("cache", new JSONObject());

        JSONObject timings = new JSONObject();
        double total = elapsedMillis(x);
        timings.put("blocked", -1);
        timings.put("dns", -1);
        timings.put("connect", -1);
        timings.put("send", 0);
        timings.put("wait", total < 0 ? 0 : total);
        timings.put("receive", 0);
        timings.put("ssl", -1);
        entry.put("timings", timings);

        if (x.resourceType != null) {
            entry.put("_resourceType", x.resourceType);
        }
        entry.put("_requestId", x.requestId);
        entry.put("_redirectIndex", x.redirectIndex);

        if (x.failed) {
            entry.put("_failed", true);
            entry.put("_failureText", x.failureText);
        }

        return entry;
    }

    private static JSONArray headers(JSONObject headers) {
        JSONArray array = new JSONArray();
        if (headers == null) {
            return array;
        }

        for (Map.Entry<String, Object> item : headers.entrySet()) {
            JSONObject h = new JSONObject();
            h.put("name", item.getKey());
            h.put(
                    "value",
                    item.getValue() == null
                            ? ""
                            : String.valueOf(item.getValue()));
            array.add(h);
        }
        return array;
    }

    private static JSONArray queryString(String url) {
        JSONArray array = new JSONArray();
        if (url == null) {
            return array;
        }

        try {
            String rawQuery = new URI(url).getRawQuery();
            if (rawQuery == null || rawQuery.isEmpty()) {
                return array;
            }

            String[] parts = rawQuery.split("&");
            for (String part : parts) {
                String[] kv = part.split("=", 2);
                JSONObject q = new JSONObject();
                q.put(
                        "name",
                        URLDecoder.decode(kv[0], "UTF-8"));
                q.put(
                        "value",
                        kv.length > 1
                                ? URLDecoder.decode(kv[1], "UTF-8")
                                : "");
                array.add(q);
            }
        } catch (Exception ignored) {
        }

        return array;
    }

    private static String headerValue(
            JSONObject headers,
            String wanted) {
        if (headers == null) {
            return "";
        }

        for (Map.Entry<String, Object> item : headers.entrySet()) {
            if (wanted.equalsIgnoreCase(item.getKey())) {
                return item.getValue() == null
                        ? ""
                        : String.valueOf(item.getValue());
            }
        }
        return "";
    }

    private static double elapsedMillis(CapturedExchange x) {
        if (x.requestTimestamp <= 0) {
            return 0D;
        }

        double end = x.finishedTimestamp > 0
                ? x.finishedTimestamp
                : x.responseTimestamp;

        if (end <= 0) {
            return 0D;
        }

        return Math.max(
                0D,
                (end - x.requestTimestamp) * 1000D);
    }

    private static String httpVersion(String protocol) {
        if (protocol == null || protocol.isEmpty()) {
            return "HTTP/1.1";
        }
        if ("h2".equalsIgnoreCase(protocol)) {
            return "HTTP/2";
        }
        return protocol.toUpperCase();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String iso(long millis) {
        SimpleDateFormat fmt =
                new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return fmt.format(new Date(millis));
    }
}
