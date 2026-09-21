package com.initialneko.cdp;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.Assume;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.*;

public class CdpConnectorTest {
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

                String visibleCookies =
                        String.valueOf(
                                cdp.page().eval(
                                        "document.cookie"));
                assertFalse(
                        visibleCookies.contains(
                                "CDP_TEST_SESSION"));

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

                PageResponse authenticated =
                        cdp.page().get(
                                "/api/authenticated");
                assertEquals(
                        200,
                        authenticated.getStatus());
                assertEquals(
                        Boolean.TRUE,
                        authenticated.json()
                                .getBoolean(
                                        "authenticated"));

                PageResponse step1 =
                        cdp.page().postJson(
                                "/api/step1",
                                "{\"query\":\"from-page-request\"}");
                assertEquals(200, step1.getStatus());
                assertEquals(
                        "A-100",
                        step1.json()
                                .getString("nextId"));

                PageResponse end =
                        cdp.page().postJson(
                                "/api/end",
                                "{\"id\":\"A-100\"}");
                assertEquals(200, end.getStatus());
                assertEquals(
                        Boolean.TRUE,
                        end.json().getBoolean("ended"));

                PageResponse report =
                        cdp.page().get(
                                "/api/report-html?id=A-100");
                assertEquals(200, report.getStatus());
                assertTrue(report.isHtml());
                assertTrue(
                        report.getBody()
                                .contains("analysis report"));

                cdp.page().eval(
                        "document.querySelector('#analysis-host').innerHTML="
                                + JSON.toJSONString(
                                        report.getBody()));
                assertTrue(
                        cdp.page().html()
                                .contains("analysis-html"));

                CapturedExchange originalStep1 =
                        waitForExchange(
                                capture,
                                "/api/step1",
                                5000L);

                PageResponse replayed =
                        cdp.page().replay(
                                originalStep1,
                                "{\"query\":\"replayed\"}");
                assertEquals(200, replayed.getStatus());
                assertTrue(
                        replayed.getBody()
                                .contains("replayed"));

                cdp.page().click("#slow");
                cdp.page().waitForJs(
                        "document.querySelector('#last-result').textContent.indexOf('slow') >= 0",
                        5000L);

                cdp.page().click("#failed");
                cdp.page().waitForJs(
                        "document.querySelector('#last-result').textContent.indexOf('error-status:500') >= 0",
                        5000L);

                Files.createDirectories(
                        Paths.get("target"));
                cdp.page().saveHtml(
                        Paths.get(
                                "target",
                                "cdp-page.html"));
                report.writeBody(
                        Paths.get(
                                "target",
                                "cdp-report.html"));

                capture.stop(5000L);

                List<CapturedExchange> exchanges =
                        capture.getExchanges();

                assertTrue(exchanges.size() >= 10);
                assertNotNull(capture.getStartHtml());
                assertNotNull(capture.getEndHtml());
                assertTrue(
                        capture.getEndHtml()
                                .contains(
                                        "analysis-html"));

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

                CapturedExchange authenticatedExchange =
                        find(
                                exchanges,
                                "/api/authenticated");
                assertNotNull(authenticatedExchange);
                assertEquals(
                        200,
                        authenticatedExchange.getStatus());

                CapturedExchange reportExchange =
                        find(
                                exchanges,
                                "/api/report-html");
                assertNotNull(reportExchange);
                assertTrue(
                        reportExchange
                                .getResponseBody()
                                .contains(
                                        "analysis report"));

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
                                .size() >= 10);

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

    private static CapturedExchange waitForExchange(
            CaptureSession capture,
            String urlPart,
            long timeoutMillis)
            throws InterruptedException {
        long deadline =
                System.currentTimeMillis()
                        + timeoutMillis;

        while (System.currentTimeMillis()
                < deadline) {
            CapturedExchange exchange =
                    find(
                            capture.getExchanges(),
                            urlPart);
            if (exchange != null
                    && exchange.getResponseBody()
                    != null) {
                return exchange;
            }
            Thread.sleep(25L);
        }

        fail(
                "Timed out waiting for captured exchange: "
                        + urlPart);
        return null;
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
