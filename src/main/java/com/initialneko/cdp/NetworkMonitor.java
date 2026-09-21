package com.initialneko.cdp;

import com.alibaba.fastjson.JSONObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class NetworkMonitor implements CdpEventListener {
    private final CdpConnection connection;
    private final PageController page;

    private final Map<String, CapturedExchange> inFlight =
            new ConcurrentHashMap<String, CapturedExchange>();
    private final Map<String, CaptureSession> owners =
            new ConcurrentHashMap<String, CaptureSession>();
    private final Map<String, Integer> redirectCounters =
            new ConcurrentHashMap<String, Integer>();

    private volatile CaptureSession active;

    NetworkMonitor(
            CdpConnection connection,
            PageController page) {
        this.connection = connection;
        this.page = page;
    }

    public synchronized CaptureSession start() {
        return start(CaptureOptions.defaults());
    }

    public synchronized CaptureSession start(CaptureOptions options) {
        if (active != null && active.isAcceptingNewRequests()) {
            throw new CdpException(
                    "A capture session is already active");
        }

        CaptureOptions actual =
                options == null
                        ? CaptureOptions.defaults()
                        : options;

        String startUrl = safePageUrl();
        String startTitle = safePageTitle();
        String startHtml = safePageHtml();

        CaptureSession session = new CaptureSession(
                this,
                actual,
                System.currentTimeMillis(),
                startUrl,
                startTitle,
                startHtml);

        active = session;
        return session;
    }

    synchronized void stop(
            CaptureSession session,
            long inFlightGraceMillis) {
        if (session == null) {
            return;
        }

        session.markStopping();
        if (active == session) {
            active = null;
        }

        long deadline =
                System.currentTimeMillis()
                        + Math.max(0L, inFlightGraceMillis);

        while (hasInFlight(session)
                && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(25L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        session.markStopped(
                safePageUrl(),
                safePageTitle(),
                safePageHtml());
    }

    @Override
    public void onEvent(
            String method,
            JSONObject params) {
        if ("Network.requestWillBeSent".equals(method)) {
            onRequestWillBeSent(params);
        } else if ("Network.responseReceived".equals(method)) {
            onResponseReceived(params);
        } else if ("Network.loadingFinished".equals(method)) {
            onLoadingFinished(params);
        } else if ("Network.loadingFailed".equals(method)) {
            onLoadingFailed(params);
        }
    }

    private void onRequestWillBeSent(JSONObject params) {
        String requestId = params.getString("requestId");
        if (requestId == null) {
            return;
        }

        CapturedExchange previous = inFlight.get(requestId);
        CaptureSession owner = owners.get(requestId);

        JSONObject redirectResponse =
                params.getJSONObject("redirectResponse");

        if (previous != null && redirectResponse != null) {
            fillResponse(
                    previous,
                    redirectResponse,
                    params.getDoubleValue("timestamp"));
            inFlight.remove(requestId);
        }

        CaptureSession target = owner;
        if (target == null
                || previous == null
                || redirectResponse == null) {
            target = active;
        }

        if (target == null
                || (!target.isAcceptingNewRequests()
                && previous == null)) {
            return;
        }

        int redirectIndex =
                redirectCounters.containsKey(requestId)
                        ? redirectCounters.get(requestId) + 1
                        : 0;
        redirectCounters.put(requestId, redirectIndex);

        JSONObject request = params.getJSONObject("request");
        if (request == null) {
            return;
        }

        CapturedExchange exchange = new CapturedExchange();
        exchange.requestId = requestId;
        exchange.redirectIndex = redirectIndex;
        exchange.requestTimestamp =
                params.getDoubleValue("timestamp");
        exchange.wallTimeSeconds =
                params.getDoubleValue("wallTime");
        exchange.resourceType = params.getString("type");
        exchange.method = request.getString("method");
        exchange.url = request.getString("url");

        JSONObject headers =
                request.getJSONObject("headers");
        exchange.requestHeaders =
                headers == null
                        ? new JSONObject()
                        : headers;

        if (target.options().isCaptureRequestBody()) {
            String postData = request.getString("postData");
            if (postData != null) {
                exchange.requestBody = truncate(
                        postData,
                        target.options().getMaxBodyChars(),
                        exchange,
                        true);
            } else if (request.getBooleanValue("hasPostData")) {
                requestPostDataAsync(target, exchange);
            }
        }

        target.add(exchange);
        inFlight.put(requestId, exchange);
        owners.put(requestId, target);
    }

    private void requestPostDataAsync(
            final CaptureSession session,
            final CapturedExchange exchange) {
        JSONObject p = new JSONObject();
        p.put("requestId", exchange.requestId);

        connection.send(
                "Network.getRequestPostData",
                p).whenComplete((result, error) -> {
            if (error != null || result == null) {
                return;
            }

            String body = result.getString("postData");
            if (body != null) {
                exchange.requestBody = truncate(
                        body,
                        session.options().getMaxBodyChars(),
                        exchange,
                        true);
            }
        });
    }

    private void onResponseReceived(JSONObject params) {
        String requestId = params.getString("requestId");
        CapturedExchange exchange =
                inFlight.get(requestId);

        if (exchange == null) {
            return;
        }

        JSONObject response =
                params.getJSONObject("response");

        if (response != null) {
            fillResponse(
                    exchange,
                    response,
                    params.getDoubleValue("timestamp"));
        }
    }

    private void onLoadingFinished(JSONObject params) {
        final String requestId =
                params.getString("requestId");
        final CapturedExchange exchange =
                inFlight.get(requestId);
        final CaptureSession session =
                owners.get(requestId);

        if (exchange == null || session == null) {
            return;
        }

        exchange.encodedDataLength =
                (long) params.getDoubleValue("encodedDataLength");
        exchange.finishedTimestamp =
                params.getDoubleValue("timestamp");

        if (!session.options().isCaptureResponseBody()) {
            finish(requestId);
            return;
        }

        JSONObject p = new JSONObject();
        p.put("requestId", requestId);

        connection.send(
                "Network.getResponseBody",
                p).whenComplete((result, error) -> {
            try {
                if (error == null && result != null) {
                    String body = result.getString("body");
                    if (body != null) {
                        exchange.responseBody = truncate(
                                body,
                                session.options().getMaxBodyChars(),
                                exchange,
                                false);
                    }
                    exchange.responseBodyBase64 =
                            result.getBooleanValue(
                                    "base64Encoded");
                }
            } finally {
                finish(requestId);
            }
        });
    }

    private void onLoadingFailed(JSONObject params) {
        String requestId = params.getString("requestId");
        CapturedExchange exchange =
                inFlight.get(requestId);

        if (exchange != null) {
            exchange.failed = true;
            exchange.failureText =
                    params.getString("errorText");
            exchange.responseTimestamp =
                    params.getDoubleValue("timestamp");
            exchange.finishedTimestamp =
                    exchange.responseTimestamp;
        }

        finish(requestId);
    }

    private void fillResponse(
            CapturedExchange exchange,
            JSONObject response,
            double timestamp) {
        exchange.responseTimestamp = timestamp;
        exchange.status =
                response.getIntValue("status");
        exchange.statusText =
                response.getString("statusText");
        exchange.mimeType =
                response.getString("mimeType");
        exchange.protocol =
                response.getString("protocol");

        JSONObject headers =
                response.getJSONObject("headers");
        exchange.responseHeaders =
                headers == null
                        ? new JSONObject()
                        : headers;
    }

    private String truncate(
            String value,
            int limit,
            CapturedExchange exchange,
            boolean request) {
        if (value.length() <= limit) {
            return value;
        }

        if (request) {
            exchange.requestBodyTruncated = true;
        } else {
            exchange.responseBodyTruncated = true;
        }

        return value.substring(0, limit);
    }

    private void finish(String requestId) {
        inFlight.remove(requestId);
        owners.remove(requestId);
        redirectCounters.remove(requestId);
    }

    private boolean hasInFlight(CaptureSession session) {
        for (CaptureSession owner : owners.values()) {
            if (owner == session) {
                return true;
            }
        }
        return false;
    }

    private String safePageUrl() {
        try {
            return page.url();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String safePageTitle() {
        try {
            return page.title();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String safePageHtml() {
        try {
            return page.html();
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
