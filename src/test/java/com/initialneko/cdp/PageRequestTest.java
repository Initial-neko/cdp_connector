package com.initialneko.cdp;

import org.junit.Test;

import static org.junit.Assert.*;

public class PageRequestTest {
    @Test
    public void replayKeepsBusinessHeadersButDropsBrowserOwnedHeaders() {
        CapturedExchange exchange =
                new CapturedExchange();
        exchange.url =
                "http://127.0.0.1/api/query";
        exchange.method = "POST";
        exchange.requestBody =
                "{\"page\":1}";
        exchange.requestHeaders.put(
                "Content-Type",
                "application/json");
        exchange.requestHeaders.put(
                "Authorization",
                "Bearer demo-token");
        exchange.requestHeaders.put(
                "X-CSRF-Token",
                "csrf-1");
        exchange.requestHeaders.put(
                "Cookie",
                "SESSION=secret");
        exchange.requestHeaders.put(
                "Host",
                "127.0.0.1");
        exchange.requestHeaders.put(
                "Sec-Fetch-Site",
                "same-origin");

        PageRequest request =
                PageRequest.replay(exchange)
                        .build();

        assertEquals(
                "POST",
                request.getMethod());
        assertEquals(
                "{\"page\":1}",
                request.getBody());
        assertEquals(
                "Bearer demo-token",
                request.getHeaders()
                        .getString(
                                "Authorization"));
        assertEquals(
                "csrf-1",
                request.getHeaders()
                        .getString(
                                "X-CSRF-Token"));
        assertFalse(
                request.getHeaders()
                        .containsKey("Cookie"));
        assertFalse(
                request.getHeaders()
                        .containsKey("Host"));
        assertFalse(
                request.getHeaders()
                        .containsKey(
                                "Sec-Fetch-Site"));
        assertEquals(
                "include",
                request.getCredentials());
    }
}
