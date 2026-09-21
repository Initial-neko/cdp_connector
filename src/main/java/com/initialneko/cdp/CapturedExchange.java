package com.initialneko.cdp;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

public final class CapturedExchange {
    String requestId;
    int redirectIndex;
    double requestTimestamp;
    double responseTimestamp;
    double finishedTimestamp;
    double wallTimeSeconds;
    long endWallTimeMillis;

    String resourceType;
    String method;
    String url;
    JSONObject requestHeaders = new JSONObject();
    String requestBody;
    boolean requestBodyTruncated;

    int status;
    String statusText;
    String mimeType;
    String protocol;
    JSONObject responseHeaders = new JSONObject();
    String responseBody;
    boolean responseBodyBase64;
    boolean responseBodyTruncated;
    long encodedDataLength;
    boolean failed;
    String failureText;

    CapturedExchange() {
    }

    public String getRequestId() { return requestId; }
    public int getRedirectIndex() { return redirectIndex; }
    public String getResourceType() { return resourceType; }
    public String getMethod() { return method; }
    public String getUrl() { return url; }
    public JSONObject getRequestHeaders() { return requestHeaders; }
    public String getRequestBody() { return requestBody; }
    public boolean isRequestBodyTruncated() { return requestBodyTruncated; }
    public int getStatus() { return status; }
    public String getStatusText() { return statusText; }
    public String getMimeType() { return mimeType; }
    public String getProtocol() { return protocol; }
    public JSONObject getResponseHeaders() { return responseHeaders; }
    public String getResponseBody() { return responseBody; }
    public boolean isResponseBodyBase64() { return responseBodyBase64; }
    public boolean isResponseBodyTruncated() { return responseBodyTruncated; }
    public boolean isFailed() { return failed; }
    public String getFailureText() { return failureText; }

    public JSONObject requestJson() {
        if (requestBody == null) return null;
        try {
            return JSON.parseObject(requestBody);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public JSONObject responseJson() {
        if (responseBody == null || responseBodyBase64) return null;
        try {
            return JSON.parseObject(responseBody);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public String summary() {
        String statusPart = status > 0 ? String.valueOf(status) : (failed ? "FAILED" : "-");
        return method + " " + url + " -> " + statusPart;
    }
}
