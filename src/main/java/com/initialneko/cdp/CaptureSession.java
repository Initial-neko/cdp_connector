package com.initialneko.cdp;

import com.alibaba.fastjson.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CaptureSession {
    private final NetworkMonitor monitor;
    private final CaptureOptions options;
    private final long startedAtMillis;
    private final String startUrl;
    private final String startTitle;
    private final String startHtml;

    private final List<CapturedExchange> exchanges =
            Collections.synchronizedList(new ArrayList<CapturedExchange>());

    private volatile long stoppedAtMillis;
    private volatile String endUrl;
    private volatile String endTitle;
    private volatile String endHtml;
    private volatile boolean acceptingNewRequests = true;

    CaptureSession(
            NetworkMonitor monitor,
            CaptureOptions options,
            long startedAtMillis,
            String startUrl,
            String startTitle,
            String startHtml) {
        this.monitor = monitor;
        this.options = options;
        this.startedAtMillis = startedAtMillis;
        this.startUrl = startUrl;
        this.startTitle = startTitle;
        this.startHtml = startHtml;
    }

    CaptureOptions options() {
        return options;
    }

    void add(CapturedExchange exchange) {
        exchanges.add(exchange);
    }

    boolean isAcceptingNewRequests() {
        return acceptingNewRequests;
    }

    void markStopping() {
        acceptingNewRequests = false;
    }

    void markStopped(String endUrl, String endTitle, String endHtml) {
        this.stoppedAtMillis = System.currentTimeMillis();
        this.endUrl = endUrl;
        this.endTitle = endTitle;
        this.endHtml = endHtml;
    }

    public void stop() {
        stop(5000L);
    }

    public void stop(long inFlightGraceMillis) {
        monitor.stop(this, inFlightGraceMillis);
    }

    public long getStartedAtMillis() { return startedAtMillis; }
    public long getStoppedAtMillis() { return stoppedAtMillis; }
    public String getStartUrl() { return startUrl; }
    public String getStartTitle() { return startTitle; }
    public String getStartHtml() { return startHtml; }
    public String getEndUrl() { return endUrl; }
    public String getEndTitle() { return endTitle; }
    public String getEndHtml() { return endHtml; }

    public List<CapturedExchange> getExchanges() {
        synchronized (exchanges) {
            return Collections.unmodifiableList(
                    new ArrayList<CapturedExchange>(exchanges));
        }
    }

    public JSONObject toHar() {
        return HarExporter.toHar(this);
    }

    public String toHarJson() {
        return toHar().toJSONString();
    }

    public void writeHar(Path path) {
        try {
            Files.write(
                    path,
                    toHarJson().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new CdpException("Failed to write HAR: " + path, e);
        }
    }
}
