package com.initialneko.cdp;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import okhttp3.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

final class CdpConnection implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(CdpConnection.class);

    private final OkHttpClient http;
    private final AtomicInteger ids = new AtomicInteger(0);
    private final Map<Integer, CompletableFuture<JSONObject>> pending =
            new ConcurrentHashMap<Integer, CompletableFuture<JSONObject>>();
    private final List<CdpEventListener> listeners =
            new CopyOnWriteArrayList<CdpEventListener>();
    private final CountDownLatch openLatch = new CountDownLatch(1);

    private volatile WebSocket webSocket;
    private volatile Throwable openFailure;

    CdpConnection(OkHttpClient http, String wsUrl) {
        this.http = http;

        Request request = new Request.Builder().url(wsUrl).build();
        this.webSocket = http.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                openLatch.countDown();
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                handleMessage(text);
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                openFailure = t;
                openLatch.countDown();
                failPending(t);
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                failPending(new CdpException(
                        "CDP WebSocket closed: " + code + " " + reason));
            }
        });

        awaitOpen();
    }

    private void awaitOpen() {
        try {
            if (!openLatch.await(10, TimeUnit.SECONDS)) {
                throw new CdpException("Timed out waiting for CDP WebSocket connection");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CdpException("Interrupted while opening CDP connection", e);
        }

        if (openFailure != null) {
            throw new CdpException("Failed to open CDP WebSocket", openFailure);
        }
    }

    CompletableFuture<JSONObject> send(String method) {
        return send(method, new JSONObject());
    }

    CompletableFuture<JSONObject> send(String method, JSONObject params) {
        int id = ids.incrementAndGet();

        JSONObject msg = new JSONObject();
        msg.put("id", id);
        msg.put("method", method);
        msg.put("params", params == null ? new JSONObject() : params);

        CompletableFuture<JSONObject> future = new CompletableFuture<JSONObject>();
        pending.put(id, future);

        boolean accepted = webSocket.send(msg.toJSONString());
        if (!accepted) {
            pending.remove(id);
            future.completeExceptionally(
                    new CdpException("WebSocket rejected command: " + method));
        }
        return future;
    }

    JSONObject sendAndWait(String method) {
        return sendAndWait(method, new JSONObject(), 10, TimeUnit.SECONDS);
    }

    JSONObject sendAndWait(String method, JSONObject params) {
        return sendAndWait(method, params, 10, TimeUnit.SECONDS);
    }

    JSONObject sendAndWait(String method, JSONObject params, long timeout, TimeUnit unit) {
        try {
            return send(method, params).get(timeout, unit);
        } catch (TimeoutException e) {
            throw new CdpException("CDP command timed out: " + method, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CdpException(
                    "Interrupted while waiting for CDP command: " + method, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new CdpException("CDP command failed: " + method, cause);
        }
    }

    void addEventListener(CdpEventListener listener) {
        listeners.add(listener);
    }

    void removeEventListener(CdpEventListener listener) {
        listeners.remove(listener);
    }

    private void handleMessage(String text) {
        JSONObject msg;
        try {
            msg = JSON.parseObject(text);
        } catch (RuntimeException e) {
            log.warn("Ignored invalid CDP JSON message", e);
            return;
        }

        Integer id = msg.getInteger("id");
        if (id != null) {
            CompletableFuture<JSONObject> future = pending.remove(id);
            if (future == null) {
                return;
            }

            JSONObject error = msg.getJSONObject("error");
            if (error != null) {
                future.completeExceptionally(new CdpException(
                        "CDP error " + error.getInteger("code")
                                + ": " + error.getString("message")));
            } else {
                JSONObject result = msg.getJSONObject("result");
                future.complete(result == null ? new JSONObject() : result);
            }
            return;
        }

        String method = msg.getString("method");
        if (method == null) {
            return;
        }

        JSONObject params = msg.getJSONObject("params");
        if (params == null) {
            params = new JSONObject();
        }

        for (CdpEventListener listener : listeners) {
            try {
                listener.onEvent(method, params);
            } catch (RuntimeException e) {
                log.warn("CDP event listener failed for {}", method, e);
            }
        }
    }

    private void failPending(Throwable t) {
        for (CompletableFuture<JSONObject> future : pending.values()) {
            future.completeExceptionally(t);
        }
        pending.clear();
    }

    @Override
    public void close() {
        WebSocket ws = webSocket;
        if (ws != null) {
            ws.close(1000, "client closing");
        }
    }
}
