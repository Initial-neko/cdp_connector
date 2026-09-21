# cdp-connector

一个面向 **Java 8 / Win7 / Chrome Remote Debugging** 的轻量 CDP Connector。

目标不是做 Selenium 替代品，而是把系统分析最常用的能力收敛成很薄的一层：

1. 对当前页面执行 JavaScript 并读取返回值。
2. 用少量便利方法操纵页面，例如点击、赋值、读取文本、导航。
3. 从明确的时间点开始，只采集之后产生的新网络请求。
4. 自动关联 request / response，并尽量拿到 request body、response body。
5. 在采集开始/结束时各保存一份页面 HTML 快照。
6. 将采集结果导出为 HAR 1.2，方便后续分析和人工检查。
7. 保留 `rawCommand(...)`，遇到暂未封装的 CDP 能力时不用改底层。

## 核心结构

```text
CdpConnector
├── page()
│   ├── eval(js)
│   ├── html() / title() / url()
│   ├── click(css)
│   ├── setValue(css, value)
│   ├── text(css) / value(css)
│   ├── navigate(url)
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

## 启动 Chrome

```bash
chrome.exe --remote-debugging-port=9222 --user-data-dir=C:/temp/cdp-profile
```

框架会从 `http://127.0.0.1:9222/json/list` 自动发现 page target。

如果 Chrome 有多个 Tab，可以按 URL 选择：

```java
CdpConnector cdp =
    CdpConnector.connectByUrl("127.0.0.1", 9222, "/business/");
```

## 页面读取与操纵

```java
try (CdpConnector cdp = CdpConnector.connect("127.0.0.1", 9222)) {
    System.out.println(cdp.page().title());
    System.out.println(cdp.page().url());

    Object result = cdp.page().eval("document.querySelectorAll('table').length");

    cdp.page().setValue("#keyword", "hello");
    cdp.page().click("#search");
    cdp.page().waitForJs(
        "document.querySelector('#result') !== null",
        5000L
    );
}
```

底层使用 `Runtime.evaluate`，所以页面 JavaScript 能做到的事情都可以直接执行。

## 从某个时点开始抓之后的新请求

```java
CaptureSession capture = cdp.network().start();

// start() 返回后新产生的请求进入本次 capture
cdp.page().click("#search");
cdp.page().waitForJs("window.searchFinished === true", 5000L);

capture.stop();
```

查看结果：

```java
for (CapturedExchange exchange : capture.getExchanges()) {
    System.out.println(exchange.summary());
    System.out.println(exchange.getRequestHeaders());
    System.out.println(exchange.getRequestBody());
    System.out.println(exchange.getStatus());
    System.out.println(exchange.getResponseHeaders());
    System.out.println(exchange.getResponseBody());
}
```

`stop()` 默认给已经开始的请求最多 5 秒收尾，避免刚触发请求就丢掉 response body。

## 页面 HTML 快照

```java
capture.getStartHtml();
capture.getStartUrl();
capture.getStartTitle();

capture.getEndHtml();
capture.getEndUrl();
capture.getEndTitle();
```

后续分析可以同时看到“开始页面状态 + 期间新请求 + 结束页面状态”。

## HAR

CDP 没有一个直接导出 HAR 的命令。本项目从 Network 事件和 `Network.getResponseBody` 组装 HAR 1.2：

```java
capture.writeHar(Paths.get("capture.har"));
String har = capture.toHarJson();
```

当前 HAR 面向系统分析可用，包含 method、URL、headers、query、request body、status、response body、mimeType、resourceType、requestId、redirect index 等。

第一版没有追求与 Chrome DevTools 导出的所有精细 timing 字段完全一致。

HAR 根节点额外保留：

```text
_pageSnapshots.startHtml
_pageSnapshots.endHtml
```

用于保存页面前后状态。

## Body 大小限制

默认每个 request / response body 最多保留 2,000,000 chars：

```java
CaptureOptions options = CaptureOptions.defaults()
    .maxBodyChars(5_000_000);

CaptureSession capture = cdp.network().start(options);
```

## Raw CDP

```java
JSONObject params = new JSONObject();
params.put("ignoreCache", true);
cdp.rawCommand("Page.reload", params);
```

以后扩展 Cookie、localStorage、screenshot、DOM、Console、WebSocket frame、Fetch interception 时不用重写连接层。

## 测试页面与真实 Chrome 集成测试

`src/test/resources/test-site/index.html` 包含：

- 输入框读取/修改
- GET JSON
- POST JSON
- HTML Fragment
- 慢请求
- HTTP 500 请求

测试服务器使用 JDK 自带的 `com.sun.net.httpserver.HttpServer`，不引入额外 Web 框架，地址是 `http://127.0.0.1:18080/`。

先启动 Chrome remote debugging :9222，然后在 Git Bash：

```bash
export CDP_IT=1
mvn test
```

`CdpConnectorTest` 会验证连接 Chrome、页面 JS、页面操纵、GET/POST/HTML/500 请求、request/response body、HTML snapshot 和 HAR。

也可以在 IDEA 直接运行 `ManualAcceptanceMain`。

## V1 边界

暂时不做 Selenium 风格的大量 Element API、鼠标坐标/键盘底层事件、下载/上传、多 Tab 自动管理、iframe 深度封装、WebSocket frame、Fetch interception，以及精确复刻 DevTools HAR timing。
