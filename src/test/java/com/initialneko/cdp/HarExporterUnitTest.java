package com.initialneko.cdp;

import com.alibaba.fastjson.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class HarExporterUnitTest {
    @Test
    public void buildsAnalysisHarWithoutRealChrome() {
        CaptureSession session = new CaptureSession(
                null,
                CaptureOptions.defaults(),
                1_700_000_000_000L,
                "http://127.0.0.1/start",
                "start",
                "<html>start</html>");

        CapturedExchange exchange = new CapturedExchange();
        exchange.requestId = "42";
        exchange.redirectIndex = 0;
        exchange.requestTimestamp = 10.0D;
        exchange.responseTimestamp = 10.1D;
        exchange.finishedTimestamp = 10.2D;
        exchange.wallTimeSeconds = 1_700_000_000D;
        exchange.resourceType = "XHR";
        exchange.method = "POST";
        exchange.url = "http://127.0.0.1/api/echo?q=1";
        exchange.requestHeaders.put("Content-Type", "application/json");
        exchange.requestBody = "{\"name\":\"Ada\"}";
        exchange.status = 200;
        exchange.statusText = "OK";
        exchange.mimeType = "application/json";
        exchange.protocol = "http/1.1";
        exchange.responseHeaders.put("Content-Type", "application/json");
        exchange.responseBody = "{\"ok\":true}";
        exchange.encodedDataLength = 11L;

        session.add(exchange);
        session.markStopping();
        session.markStopped(
                "http://127.0.0.1/end",
                "end",
                "<html>end</html>");

        JSONObject har = session.toHar();
        JSONObject log = har.getJSONObject("log");
        assertEquals("1.2", log.getString("version"));
        assertEquals(1, log.getJSONArray("entries").size());

        JSONObject entry = log.getJSONArray("entries").getJSONObject(0);
        assertEquals("POST", entry.getJSONObject("request").getString("method"));
        assertEquals(200, entry.getJSONObject("response").getIntValue("status"));
        assertEquals("{\"ok\":true}", entry.getJSONObject("response")
                .getJSONObject("content").getString("text"));

        JSONObject snapshots = har.getJSONObject("_pageSnapshots");
        assertEquals("<html>start</html>", snapshots.getString("startHtml"));
        assertEquals("<html>end</html>", snapshots.getString("endHtml"));
    }
}
