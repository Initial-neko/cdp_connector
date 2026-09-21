package com.initialneko.cdp;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;

final class ChromeDiscovery {
    private ChromeDiscovery() {
    }

    static String findPageWebSocketUrl(OkHttpClient http, String host, int port) {
        return findPageWebSocketUrl(http, host, port, null);
    }

    static String findPageWebSocketUrl(OkHttpClient http, String host, int port, String urlContains) {
        String endpoint = "http://" + host + ":" + port + "/json/list";
        Request request = new Request.Builder().url(endpoint).get().build();

        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new CdpException("Chrome discovery failed: HTTP " + response.code());
            }

            JSONArray targets = JSON.parseArray(response.body().string());
            for (int i = 0; i < targets.size(); i++) {
                JSONObject target = targets.getJSONObject(i);
                if ("page".equals(target.getString("type"))) {
                    String url = target.getString("url");
                    if (urlContains != null && (url == null || !url.contains(urlContains))) {
                        continue;
                    }
                    String ws = target.getString("webSocketDebuggerUrl");
                    if (ws != null && !ws.trim().isEmpty()) {
                        return ws;
                    }
                }
            }
            throw new CdpException("No matching debuggable page target found at " + endpoint
                    + (urlContains == null ? "" : " urlContains=" + urlContains));
        } catch (IOException e) {
            throw new CdpException("Failed to discover Chrome target at " + endpoint, e);
        }
    }
}
