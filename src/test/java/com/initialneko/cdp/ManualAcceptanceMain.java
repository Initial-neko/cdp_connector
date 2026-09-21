package com.initialneko.cdp;

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
                cdp.page().navigate(
                        "http://127.0.0.1:18080/");
                cdp.page().waitForSelector(
                        "#post-json",
                        5000L);

                System.out.println(
                        "title=" + cdp.page().title());
                System.out.println(
                        "url=" + cdp.page().url());

                CaptureSession capture =
                        cdp.network().start();

                cdp.page().setValue(
                        "#name",
                        "Ada");

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
                        "HAR written to target/manual-acceptance.har");
            }
        }
    }
}
