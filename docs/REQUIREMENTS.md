# V0.1 Requirements and Acceptance

## Goal

`cdp-connector` is a lightweight Java 8 / Windows 7 friendly Chrome DevTools Protocol helper for system analysis. It is not an Agent and not a Selenium replacement.

The main workflow is:

1. Attach to an existing Chrome page through remote debugging.
2. Read or manipulate the page with JavaScript and a few convenience APIs.
3. Start a capture at an explicit point in time.
4. Collect only network requests that begin after that point, while allowing already-owned in-flight requests to finish after `stop()`.
5. Preserve request/response metadata and bodies, including `text/html` response bodies.
6. Send authenticated requests from the current Chrome page context.
7. Replay a captured request with the original or a replacement body.
8. Preserve page HTML snapshots before and after the capture.
9. Save current DOM HTML or an HTTP HTML response to disk.
10. Export the result as HAR 1.2 for later analysis.

## Required page capabilities

- Connect to the first page target.
- Select a page target by URL fragment.
- Execute arbitrary JavaScript and return values by value.
- Await Promise-returning JavaScript.
- Read title, URL, and current document HTML.
- Save current document HTML.
- Click by CSS selector.
- Read/set input value.
- Read element text.
- Navigate/reload.
- Wait for a selector or a JavaScript condition.
- GET and POST JSON in the current page context.
- Send a custom `PageRequest`.
- Replay a `CapturedExchange`.
- Save a `PageResponse` body.
- Keep a raw CDP command escape hatch.

## Browser-context request semantics

`page.request(...)` must execute from the attached browser page rather than from Java's OkHttp client.

Default request credentials are `include`.

This allows Chrome to apply browser-managed authentication state such as same-origin cookies and HttpOnly cookies.

Replay must preserve useful business headers while avoiding headers that browser JavaScript is not allowed to set directly.

Examples retained when present:

- Authorization
- Content-Type
- X-CSRF-Token
- X-XSRF-TOKEN
- tenant-id
- org-code

Examples deliberately not replayed directly:

- Cookie
- Host
- Content-Length
- Origin
- Referer
- Sec-*
- Proxy-*

The browser remains responsible for Cookie and browser-owned headers.

Application-specific authentication that exists only in a custom Axios/fetch interceptor is not magically inherited by a fresh raw fetch. For those systems, replaying a captured request or explicitly supplying the business header is the supported V0.1 path.

Browser CORS rules remain in effect for cross-origin requests.

## Required network capture semantics

`network().start()` defines the capture boundary.

- A request whose `Network.requestWillBeSent` occurs before `start()` must not enter the session.
- A request whose `Network.requestWillBeSent` occurs after `start()` must enter the session.
- Requests created by normal page actions and requests created by `page.request()` are captured through the same Network domain.
- `stop()` stops accepting unrelated new requests.
- Requests already owned by the session may finish during the grace period so response status/headers/body are not lost.
- Redirect hops belonging to an already-owned request remain part of that capture even if the redirect happens during stop grace.
- Request and response are correlated by CDP request id, with redirect index retained because CDP can reuse a request id across redirect hops.

For each captured exchange, retain when available:

- request id and redirect index
- URL and method
- resource type
- request headers and body
- response status/status text
- response headers
- MIME type and protocol
- response body and base64 flag
- failure information
- timing data sufficient for analysis-grade HAR output

Large bodies may be truncated according to `CaptureOptions.maxBodyChars` and must expose the truncation flag.

## HTML semantics

There are three different HTML concepts:

1. **Current page DOM HTML**: `page.html()` or `page.saveHtml(...)`.
2. **HTTP HTML response**: `PageResponse.getBody()` or a captured exchange whose MIME type is `text/html`.
3. **Capture boundary snapshots**: `capture.getStartHtml()` and `capture.getEndHtml()`.

There is no special “request HTML” concept. The request side consists of URL/query, headers, method, and optional request body.

## HAR scope

V0.1 exports analysis-oriented HAR 1.2 with request/response headers, query string, request body, response body, status, MIME type, resource type, request id, redirect index, and page snapshots.

V0.1 does not promise byte-for-byte parity with Chrome DevTools HAR. Precise DNS/connect/SSL/TTFB timings, ExtraInfo headers/cookies, WebSocket frames, Service Worker internals, and streaming/very large bodies can be added later if analysis requires them.

## Acceptance test page

The local test site must verify at least:

- page navigation and selector wait
- JavaScript return value
- input value manipulation
- GET JSON request/response
- POST JSON request body and response body
- HttpOnly session cookie is hidden from JavaScript
- browser-context authenticated request still succeeds
- API 1 -> API 2 chained request flow
- captured request replay with replacement body
- `text/html` response body
- response HTML saved to disk
- current DOM HTML saved to disk
- delayed response and stop grace behavior
- HTTP 500 response body
- start/end page HTML snapshot
- capture boundary (initial document request is not included when capture begins after page load)
- HAR 1.2 generation

The integration test is intentionally gated behind `CDP_IT=1` because it needs a real Chrome instance with remote debugging enabled.
