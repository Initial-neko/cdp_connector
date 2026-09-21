package com.initialneko.cdp;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.Assume;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.*;

public class CdpConnectorIT {
    @Test
    public void fullPageAndNetworkFlow() throws Exception {
        Assume.assumeTrue(
                "Set CDP_IT=1 to run real Chrome integration test",
                "1".equals(System.getenv("CDP_IT")));

        try (TestPageServer server =
                     new TestPageServer(18080)) {
            server.start();

            try (CdpConnector cdp =
                         CdpConnector.connect(
                                 "127.0.0.1",
                                 9222)) {
                cdp.page().navigate(
                        "http://127.0.0.1:18080/");
                cdp.page().waitForSelector(
                        "#post-json",
                        5000L);

                assertEquals(
                        "CDP Connector Test Page",
                        cdp.page().title());
                assertTrue(
                        cdp.page()
                                .html()
                                .contains(
                                        "CDP Connector Test Page"));

                Object objectResult =
                        cdp.page().eval(
                                "({ok:true, answer:42})");
                assertNotNull(objectResult);

                CaptureSession capture =
                        cdp.network().start();

                cdp.page().setValue(
                        "#name",
                        "Ada");
                assertEquals(
                        "Ada",
                        cdp.page().value("#name"));

                cdp.page().click("#get-json");
                cdp.page().waitForJs(
                        "document.querySelector('#last-result').textContent.indexOf('get-json') >= 0",
                        5000L);

                cdp.page().click("#post-json");
                cdp.page().waitForJs(
                        "document.querySelector('#last-result').textContent.indexOf('Ada') >= 0",
                        5000L);

                cdp.page().click("#load-html");
                cdp.page().waitForSelector(
                        "#server-fragment",
                        5000L);

                cdp.page().click("#slow");
                cdp.page().waitForJs(
                        "document.querySelector('#last-result').textContent.indexOf('slow') >= 0",
                        5000L);

                cdp.page().click("#failed");
                cdp.page().waitForJs(
                        "document.querySelector('#last-result').textContent.indexOf('error-status:500') >= 0",
                        5000L);

                capture.stop(5000L);

                List<CapturedExchange> exchanges =
                        capture.getExchanges();

                assertTrue(exchanges.size() >= 5);
                assertNotNull(capture.getStartHtml());
                assertNotNull(capture.getEndHtml());
                assertTrue(
                        capture.getEndHtml()
                                .contains(
                                        "server-fragment"));

                CapturedExchange post =
                        find(exchanges, "/api/echo");
                assertNotNull(post);
                assertEquals(
                        "POST",
                        post.getMethod());
                assertTrue(
                        post.getRequestBody()
                                .contains("Ada"));
                assertTrue(
                        post.getResponseBody()
                                .contains("requestBody"));

                CapturedExchange fragment =
                        find(exchanges, "/fragment");
                assertNotNull(fragment);
                assertTrue(
                        fragment.getResponseBody()
                                .contains(
                                        "fragment-loaded"));

                CapturedExchange error =
                        find(exchanges, "/api/error");
                assertNotNull(error);
                assertEquals(
                        500,
                        error.getStatus());
                assertTrue(
                        error.getResponseBody()
                                .contains(
                                        "intentional-test-error"));

                assertNull(
                        findExact(
                                exchanges,
                                "http://127.0.0.1:18080/"));

                JSONObject har = capture.toHar();
                assertEquals(
                        "1.2",
                        har.getJSONObject("log")
                                .getString("version"));
                assertTrue(
                        har.getJSONObject("log")
                                .getJSONArray("entries")
                                .size() >= 5);

                capture.writeHar(
                        Paths.get(
                                "target",
                                "cdp-connector-test.har"));

                assertNotNull(
                        JSON.parseObject(
                                capture.toHarJson()));
            }
        }
    }

    private static CapturedExchange findExact(
            List<CapturedExchange> list,
            String url) {
        for (CapturedExchange exchange : list) {
            if (url.equals(exchange.getUrl())) {
                return exchange;
            }
        }
        return null;
    }

    private static CapturedExchange find(
            List<CapturedExchange> list,
            String urlPart) {
        for (CapturedExchange exchange : list) {
            if (exchange.getUrl() != null
                    && exchange.getUrl()
                    .contains(urlPart)) {
                return exchange;
            }
        }
        return null;
    }
}
