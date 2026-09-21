package com.initialneko.cdp;

import com.alibaba.fastjson.JSONObject;
import okhttp3.OkHttpClient;

import java.util.concurrent.TimeUnit;

public final class CdpConnector implements AutoCloseable {
    private final OkHttpClient http;
    private final CdpConnection connection;
    private final PageController page;
    private final NetworkMonitor network;

    private CdpConnector(OkHttpClient http, CdpConnection connection) {
        this.http = http;
        this.connection = connection;
        this.page = new PageController(connection);
        this.network = new NetworkMonitor(connection, page);

        connection.addEventListener(network);

        connection.sendAndWait("Runtime.enable", new JSONObject());
        connection.sendAndWait("Page.enable", new JSONObject());

        JSONObject networkEnable = new JSONObject();
        networkEnable.put("maxTotalBufferSize", 100 * 1024 * 1024);
        networkEnable.put("maxResourceBufferSize", 10 * 1024 * 1024);
        connection.sendAndWait("Network.enable", networkEnable);
    }

    public static CdpConnector connect(String host, int port) {
        OkHttpClient http = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();

        String wsUrl =
                ChromeDiscovery.findPageWebSocketUrl(http, host, port);
        CdpConnection connection = new CdpConnection(http, wsUrl);
        return new CdpConnector(http, connection);
    }

    public static CdpConnector connectByUrl(
            String host,
            int port,
            String urlContains) {
        OkHttpClient http = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();

        String wsUrl = ChromeDiscovery.findPageWebSocketUrl(
                http, host, port, urlContains);
        CdpConnection connection = new CdpConnection(http, wsUrl);
        return new CdpConnector(http, connection);
    }

    public static CdpConnector connect(String webSocketDebuggerUrl) {
        OkHttpClient http = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();

        return new CdpConnector(
                http,
                new CdpConnection(http, webSocketDebuggerUrl));
    }

    public PageController page() {
        return page;
    }

    public NetworkMonitor network() {
        return network;
    }

    public JSONObject rawCommand(String method, JSONObject params) {
        return connection.sendAndWait(
                method,
                params == null ? new JSONObject() : params);
    }

    @Override
    public void close() {
        connection.removeEventListener(network);
        connection.close();
        http.dispatcher().executorService().shutdown();
        http.connectionPool().evictAll();
    }
}
