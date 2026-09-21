# cdp-connector

一个面向 **Java 8 / Win7 / Chrome Remote Debugging** 的轻量 CDP Connector。

目标不是做 Selenium 替代品，而是把系统分析最常用的能力收敛成很薄的一层：

1. 对当前页面执行 JavaScript 并读取返回值。
2. 用少量便利方法操纵页面，例如点击、赋值、读取文本、导航。
3. 从明确的时间点开始，只采集之后产生的新网络请求。
4. 自动关联 request / response，并尽量拿到 request body、response body。
5. 在当前页面登录态中主动发送 GET/POST/自定义请求。
6. 将抓到的真实请求直接 replay，并允许替换 body。
7. 保存页面 DOM HTML 或接口返回的 HTML。
8. 在采集开始/结束时保存页面 HTML 快照。
9. 将采集结果导出为 HAR 1.2。
10. 保留 `rawCommand(...)`，暂未封装的 CDP 能力仍可直接调用。

## 核心结构

```text
CdpConnector
├── page()
│   ├── eval(js)
│   ├── html() / saveHtml(path)
│   ├── title() / url()
│   ├── click(css)
│   ├── setValue(css, value)
│   ├── text(css) / value(css)
│   ├── navigate(url)
│   ├── get(url)
│   ├── postJson(url, body)
│   ├── request(PageRequest)
│   ├── replay(CapturedExchange)
│   └── waitForJs(...)
├── network()
│   └── start()
│       ├── request / response 自动关联
│       ├── request body / response body
│       ├── start/end HTML snapshot
│       └── HAR export
└── rawCommand(method, params)
```

底层保持轻量：

```text
Java 8
+ OkHttp 3.14.9 WebSocket
+ Fastjson 1.2.84
+ Chrome DevTools Protocol
```

没有 Spring、Selenium、Playwright、Netty。

更完整的结构和业务流程见 `docs/ARCHITECTURE.md`。

## 启动 Chrome

```bash
chrome.exe --remote-debugging-port=9222 --user-data-dir=C:/temp/cdp-profile
```

框架会从 `http://127.0.0.1:9222/json/list` 自动发现 page target。

如果 Chrome 有多个 Tab，可以按 URL 选择：

```java
CdpConnector cdp =
    CdpConnector.connectByUrl(
        "127.0.0.1",
        9222,
        "/business/"
    );
```

## 页面读取与操纵

```java
try (CdpConnector cdp =
         CdpConnector.connect(
             "127.0.0.1",
             9222
         )) {

    System.out.println(cdp.page().title());
    System.out.println(cdp.page().url());

    Object result =
        cdp.page().eval(
            "document.querySelectorAll('table').length"
        );

    cdp.page().setValue(
        "#keyword",
        "hello"
    );

    cdp.page().click(
        "#search"
    );

    cdp.page().waitForJs(
        "document.querySelector('#result') !== null",
        5000L
    );
}
```

## 当前页面登录态请求

最简单：

```java
PageResponse user =
    cdp.page().get(
        "/api/currentUser"
    );

PageResponse query =
    cdp.page().postJson(
        "/api/order/query",
        "{\"page\":1,\"size\":20}"
    );

System.out.println(
    query.getStatus()
);

System.out.println(
    query.getBody()
);

Object firstId =
    query.jsonPath(
        "$.data.list[0].id"
    );
```

自定义 header：

```java
PageResponse response =
    cdp.page().request(
        PageRequest.builder(
                "/api/order/query"
            )
            .method("POST")
            .header(
                "Content-Type",
                "application/json"
            )
            .header(
                "X-CSRF-Token",
                "xxx"
            )
            .body(
                "{\"page\":1}"
            )
            .build()
    );
```

这里不是 Java OkHttp 直接访问服务器，而是通过 `Runtime.evaluate` 在当前 Chrome 页面里执行 `fetch(...)`。

因此同源请求默认使用：

```text
credentials = include
```

Chrome 会负责携带符合规则的浏览器 Cookie，包括 JavaScript 无法读取的 HttpOnly Cookie。

如果系统使用的是业务代码动态添加的 Authorization / CSRF / tenant 等 header，优先 replay 一次真实请求。

## Replay

先通过真实页面操作抓到请求：

```java
CapturedExchange original =
    ...;
```

原样重放：

```java
PageResponse same =
    cdp.page().replay(
        original
    );
```

替换 body：

```java
PageResponse modified =
    cdp.page().replay(
        original,
        "{\"page\":2,\"size\":100}"
    );
```

replay 会复制适合由业务层控制的 header，例如：

```text
Authorization
Content-Type
X-CSRF-Token
X-XSRF-TOKEN
tenant-id
org-code
```

不会强行复制由浏览器管理或禁止 JS 设置的 header，例如：

```text
Cookie
Host
Content-Length
Origin
Referer
Sec-*
Proxy-*
```

Cookie 仍由当前 Chrome 登录态自动处理。

## 从某个时点开始抓之后的新请求

```java
CaptureSession capture =
    cdp.network().start();

cdp.page().click(
    "#search"
);

PageResponse response =
    cdp.page().get(
        "/api/detail"
    );

capture.stop();
```

页面点击产生的请求与 `page.request()` 主动产生的请求都会进入同一个 CaptureSession。

查看结果：

```java
for (CapturedExchange exchange :
        capture.getExchanges()) {

    System.out.println(
        exchange.summary()
    );

    System.out.println(
        exchange.getRequestBody()
    );

    System.out.println(
        exchange.getResponseBody()
    );
}
```

`stop()` 默认给已经开始的请求最多 5 秒收尾，避免刚触发请求就丢掉 response body。

## HTML

当前 DOM：

```java
String html =
    cdp.page().html();

cdp.page().saveHtml(
    Paths.get(
        "target/page.html"
    )
);
```

接口返回 HTML：

```java
PageResponse html =
    cdp.page().get(
        "/api/report-html"
    );

html.writeBody(
    Paths.get(
        "target/report.html"
    )
);
```

Capture 页面边界：

```java
capture.getStartHtml();
capture.getEndHtml();
```

这三个 HTML 概念是不同的：

```text
当前 DOM HTML
HTTP response HTML
Capture start/end HTML snapshot
```

## HAR

```java
capture.writeHar(
    Paths.get(
        "capture.har"
    )
);

String har =
    capture.toHarJson();
```

当前 HAR 面向系统分析，包含 method、URL、headers、query、request body、status、response body、mimeType、resourceType、requestId、redirect index 等。

第一版不追求与 Chrome DevTools 导出的所有精细 timing 字段完全一致。

## Body 大小限制

默认每个 request / response body 最多保留 2,000,000 chars：

```java
CaptureOptions options =
    CaptureOptions.defaults()
        .maxBodyChars(
            5_000_000
        );

CaptureSession capture =
    cdp.network()
        .start(options);
```

## 测试

普通 Java 8 单测：

```bash
mvn test
```

真实 Chrome 全链路：

```bash
export CDP_IT=1
mvn test
```

真实测试会验证：

- 页面导航和 JS。
- 点击/输入。
- GET / POST。
- request / response body。
- HttpOnly 登录态继承。
- Java 主动发登录态 API。
- API1 -> API2 链式调用。
- request replay。
- HTML response 保存。
- 当前 DOM HTML 保存。
- capture 时间边界。
- HTTP 500。
- HAR。

也可以在 IDEA 直接运行：

```text
ManualAcceptanceMain
```

输出：

```text
target/manual-acceptance.har
target/manual-page.html
target/manual-report.html
```

## V1 边界

暂时不做 Selenium 风格的大量 Element API、鼠标坐标/键盘底层事件、下载/上传、多 Tab 自动管理、iframe 深度封装、WebSocket frame、Fetch interception，以及精确复刻 DevTools HAR timing。

对于跨域 API，浏览器本身的 CORS 规则仍然生效；`page.request()` 的定位首先是当前业务页面同源/已有登录态的系统分析。
