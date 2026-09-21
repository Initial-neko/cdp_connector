package com.initialneko.cdp;

import com.alibaba.fastjson.JSON;

import java.nio.file.Files;
import java.nio.file.Paths;

public final class ManualAcceptanceMain {
    public static void main(String[] args)
            throws Exception {
        try (TestPageServer server =
                     new TestPageServer(18080)) {
            server.start();

            try (CdpConnector cdp =
                         CdpConnector.connect(
                                 "127.0.0.1",
                                 9222)) {
                // 1. Enter a page.
                cdp.page().navigate(
                        "http://127.0.0.1:18080/");
                cdp.page().waitForSelector(
                        "#api-chain",
                        5000L);

                System.out.println(
                        "title=" + cdp.page().title());
                System.out.println(
                        "url=" + cdp.page().url());

                // 2. Execute JavaScript before the analysis window.
                Object jsReady =
                        cdp.page().eval(
                                "window.manualFlow='ready'; window.manualFlow");
                System.out.println(
                        "js=" + jsReady);

                // 3. Start capturing only new requests from this point.
                CaptureSession capture =
                        cdp.network().start();

                // 4. Operate the page just like a user.
                cdp.page().setValue(
                        "#name",
                        "Ada");
                cdp.page().click(
                        "#get-json");
                cdp.page().waitForJs(
                        "document.querySelector('#last-result').textContent.indexOf('get-json') >= 0",
                        5000L);

                // 5. Send an authenticated API directly in the page context.
                PageResponse auth =
                        cdp.page().get(
                                "/api/authenticated");
                System.out.println(
                        "auth=" + auth.summary());

                PageResponse step1 =
                        cdp.page().postJson(
                                "/api/step1",
                                "{\"query\":\"Ada\"}");
                String nextId =
                        step1.json()
                                .getString("nextId");
                System.out.println(
                        "step1 nextId=" + nextId);

                // 6. Send the next API using data from the previous response.
                PageResponse end =
                        cdp.page().postJson(
                                "/api/end",
                                "{\"id\":"
                                        + JSON.toJSONString(nextId)
                                        + "}");
                System.out.println(
                        "end=" + end.summary());

                // 7. Download an authenticated HTML response.
                PageResponse report =
                        cdp.page().get(
                                "/api/report-html?id="
                                        + nextId);
                System.out.println(
                        "report=" + report.summary());

                // 8. Use JavaScript again to inject/analyse the returned HTML.
                cdp.page().eval(
                        "document.querySelector('#analysis-host').innerHTML="
                                + JSON.toJSONString(
                                        report.getBody()));
                Object reportText =
                        cdp.page().eval(
                                "document.querySelector('#analysis-host').innerText");
                System.out.println(
                        "reportText=" + reportText);

                Files.createDirectories(
                        Paths.get("target"));
                report.writeBody(
                        Paths.get(
                                "target",
                                "manual-report.html"));
                cdp.page().saveHtml(
                        Paths.get(
                                "target",
                                "manual-page.html"));

                // 9. Stop accepting new requests and let owned requests finish.
                capture.stop(5000L);

                // 10. Inspect everything captured during this analysis window.
                for (CapturedExchange exchange
                        : capture.getExchanges()) {
                    System.out.println(
                            exchange.summary());

                    if (exchange.getRequestBody()
                            != null) {
                        System.out.println(
                                "  request="
                                        + exchange
                                        .getRequestBody());
                    }

                    if (exchange.getResponseBody()
                            != null) {
                        System.out.println(
                                "  response="
                                        + exchange
                                        .getResponseBody());
                    }
                }

                capture.writeHar(
                        Paths.get(
                                "target",
                                "manual-acceptance.har"));

                System.out.println(
                        "HAR  : target/manual-acceptance.har");
                System.out.println(
                        "PAGE : target/manual-page.html");
                System.out.println(
                        "HTML : target/manual-report.html");
            }
        }
    }
}
