# Architecture and Analysis Workflow

## 1. Positioning

`cdp-connector` is a lightweight Java 8 connector around an existing logged-in Chrome page.

It has three responsibilities:

```text
CdpConnector
├── PageController
│   ├── page state: title / url / html
│   ├── page actions: click / setValue / navigate / wait
│   ├── JavaScript: eval / evalObject
│   └── browser-context HTTP: get / postJson / request / replay
│
├── NetworkMonitor
│   └── CaptureSession
│       ├── CapturedExchange[]
│       ├── request / response correlation
│       ├── response body collection
│       ├── start/end page HTML snapshots
│       └── HAR 1.2 export
│
└── rawCommand(...)
    └── escape hatch for CDP capabilities not wrapped yet
```

The page-control path and the network-observation path share the same Chrome page target.

That means an HTTP request sent through `page.request(...)` is also visible to `NetworkMonitor` when a capture session is active.

## 2. Logged-in request model

`PageController.request(PageRequest)` does not send the request from Java/OkHttp.

It executes `fetch(...)` inside the attached Chrome page through `Runtime.evaluate`.

```text
Java
  |
  | Runtime.evaluate
  v
logged-in Chrome page
  |
  | fetch(..., credentials = include)
  v
business system API
```

This is important because browser-owned authentication state remains browser-owned.

For a normal same-origin request, Chrome can automatically attach cookies, including HttpOnly cookies that JavaScript cannot read.

For application-specific headers such as:

- Authorization
- X-CSRF-Token
- X-XSRF-TOKEN
- tenant-id
- org-code

use either an explicit `PageRequest.header(...)` or `page.replay(capturedExchange)`.

Replay copies useful business headers from the captured request but deliberately drops browser-owned/forbidden headers such as Cookie, Host, Content-Length, Origin, Referer, Sec-* and Proxy-*.

## 3. Main analysis workflow

The intended system-analysis flow is:

```text
attach to existing Chrome
        |
        v
enter target page
        |
        v
wait until page is ready
        |
        v
optional JavaScript inspection
        |
        v
network.start()
        |
        +--> click / input / normal page operations
        |
        +--> page.request(API 1)
        |       |
        |       +--> parse JSON result
        |
        +--> page.request(API 2 using API 1 result)
        |
        +--> page.request(HTML endpoint)
        |       |
        |       +--> PageResponse.body
        |       +--> writeBody(report.html)
        |
        +--> eval(JS using returned data/HTML)
        |
        +--> page.saveHtml(current-page.html)
        |
        v
capture.stop()
        |
        +--> CapturedExchange[]
        +--> capture.har
        +--> start HTML snapshot
        +--> end HTML snapshot
```

## 4. Concrete example

```java
try (CdpConnector cdp =
         CdpConnector.connectByUrl(
                 "127.0.0.1",
                 9222,
                 "/business/")) {

    // A. Enter the page.
    cdp.page().navigate(
            "https://erp.example.com/order/list");
    cdp.page().waitForSelector(
            "#query",
            10000L);

    // B. Inspect the page with JS.
    Object tableCount =
            cdp.page().eval(
                    "document.querySelectorAll('table').length");

    // C. Start the analysis window.
    CaptureSession capture =
            cdp.network().start();

    // D. Operate the page.
    cdp.page().setValue(
            "#orderNo",
            "PO-10001");
    cdp.page().click(
            "#query");

    // E. Send API 1 in the current login context.
    PageResponse first =
            cdp.page().postJson(
                    "/api/order/query",
                    "{\"page\":1,\"size\":20}");

    String orderId =
            String.valueOf(
                    first.jsonPath(
                            "$.data.list[0].id"));

    // F. Send API 2 using data returned by API 1.
    PageResponse detail =
            cdp.page().postJson(
                    "/api/order/detail",
                    "{\"id\":"
                            + JSON.toJSONString(orderId)
                            + "}");

    // G. Fetch HTML using the same browser session.
    PageResponse html =
            cdp.page().get(
                    "/api/order/print?id="
                            + orderId);

    html.writeBody(
            Paths.get(
                    "target/order-print.html"));

    // H. Run JS again using the returned content.
    cdp.page().eval(
            "document.querySelector('#preview').innerHTML="
                    + JSON.toJSONString(
                            html.getBody()));

    // I. Save the DOM after all operations.
    cdp.page().saveHtml(
            Paths.get(
                    "target/page-final.html"));

    // J. Close the network window and export.
    capture.stop();
    capture.writeHar(
            Paths.get(
                    "target/analysis.har"));
}
```

## 5. Replay flow

When a real page operation already produced a request, prefer replay for unknown enterprise authentication headers.

```java
CapturedExchange original =
        ...; // find it from CaptureSession

PageResponse sameRequest =
        cdp.page().replay(original);

PageResponse modified =
        cdp.page().replay(
                original,
                "{\"page\":2,\"size\":100}");
```

Typical analysis pattern:

```text
click real UI
   -> capture real request
   -> inspect request headers/body
   -> replay unchanged
   -> modify one parameter
   -> replay again
   -> compare responses
```

This is especially useful when the application adds authentication or CSRF headers itself.

## 6. HTML has three meanings in this project

### Page DOM HTML

`page.html()` / `page.saveHtml(...)`

This is the current DOM after JavaScript has modified the page.

### HTTP HTML response

`PageResponse.getBody()` or `CapturedExchange.getResponseBody()`

This is the server response body for an endpoint whose content type is HTML.

### Capture snapshots

`capture.getStartHtml()` and `capture.getEndHtml()`

These describe the page state at the analysis-window boundaries.

They are different artifacts and should not be merged conceptually.

## 7. Test architecture

### Unit tests

`mvn test` without `CDP_IT` verifies logic that does not require Chrome, including:

- HAR generation
- replay header filtering
- normal Java compilation

### Real Chrome integration test

Start Chrome with remote debugging:

```bash
chrome.exe --remote-debugging-port=9222 --user-data-dir=C:/temp/cdp-profile
```

Then in Git Bash:

```bash
export CDP_IT=1
mvn test
```

The local test server sets an HttpOnly cookie on the test page.

The integration test verifies that:

1. `document.cookie` cannot read that session cookie.
2. `page.get("/api/authenticated")` still succeeds because Chrome sends the cookie.
3. Button-generated requests and Java-generated `page.request()` requests are both captured.
4. API 1 -> API 2 chaining works.
5. Captured request replay works with a modified body.
6. HTML response download works.
7. Current DOM HTML can be saved.
8. The initial page document request is outside a capture started after page load.
9. HAR contains the captured analysis window.

### Manual acceptance

Run `ManualAcceptanceMain` from IDEA after starting Chrome.

It produces:

```text
target/manual-acceptance.har
target/manual-page.html
target/manual-report.html
```

This is the easiest human-readable end-to-end verification.
