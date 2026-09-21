package com.initialneko.cdp;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

public final class PageController {
    private final CdpConnection connection;

    PageController(CdpConnection connection) {
        this.connection = connection;
    }

    public Object eval(String javascript) {
        JSONObject params = new JSONObject();
        params.put("expression", javascript);
        params.put("returnByValue", true);
        params.put("awaitPromise", true);

        JSONObject result = connection.sendAndWait("Runtime.evaluate", params);
        JSONObject exception = result.getJSONObject("exceptionDetails");
        if (exception != null) {
            throw new CdpException(
                    "JavaScript evaluation failed: " + exception.toJSONString());
        }

        JSONObject remote = result.getJSONObject("result");
        return remote == null ? null : remote.get("value");
    }

    public JSONObject evalObject(String javascript) {
        Object value = eval(javascript);
        if (value == null) {
            return null;
        }
        if (value instanceof JSONObject) {
            return (JSONObject) value;
        }
        return JSON.parseObject(JSON.toJSONString(value));
    }

    public String title() {
        Object value = eval("document.title");
        return value == null ? null : String.valueOf(value);
    }

    public String url() {
        Object value = eval("location.href");
        return value == null ? null : String.valueOf(value);
    }

    public String html() {
        Object value = eval("document.documentElement.outerHTML");
        return value == null ? null : String.valueOf(value);
    }

    public void saveHtml(Path path) {
        try {
            String current = html();
            Files.write(
                    path,
                    (current == null ? "" : current)
                            .getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new CdpException(
                    "Failed to write page HTML: " + path,
                    e);
        }
    }

    public String text(String cssSelector) {
        String selector = JSON.toJSONString(cssSelector);
        Object value = eval(
                "(function(){var e=document.querySelector(" + selector
                        + ");return e?e.textContent:null;})()");
        return value == null ? null : String.valueOf(value);
    }

    public String value(String cssSelector) {
        String selector = JSON.toJSONString(cssSelector);
        Object value = eval(
                "(function(){var e=document.querySelector(" + selector
                        + ");return e?e.value:null;})()");
        return value == null ? null : String.valueOf(value);
    }

    public void setValue(String cssSelector, String value) {
        String selector = JSON.toJSONString(cssSelector);
        String val = JSON.toJSONString(value);
        eval("(function(){var e=document.querySelector(" + selector + ");"
                + "if(!e){throw new Error('Element not found: '+"
                + selector + ");}"
                + "e.value=" + val + ";"
                + "e.dispatchEvent(new Event('input',{bubbles:true}));"
                + "e.dispatchEvent(new Event('change',{bubbles:true}));"
                + "return true;})()");
    }

    public void click(String cssSelector) {
        String selector = JSON.toJSONString(cssSelector);
        eval("(function(){var e=document.querySelector(" + selector + ");"
                + "if(!e){throw new Error('Element not found: '+"
                + selector + ");}"
                + "e.click();return true;})()");
    }

    public void navigate(String url) {
        JSONObject params = new JSONObject();
        params.put("url", url);
        JSONObject result = connection.sendAndWait("Page.navigate", params);
        String errorText = result.getString("errorText");
        if (errorText != null && !errorText.isEmpty()) {
            throw new CdpException("Navigation failed: " + errorText);
        }
    }

    public void reload() {
        connection.sendAndWait("Page.reload", new JSONObject());
    }

    public PageResponse get(String url) {
        return request(PageRequest.get(url));
    }

    public PageResponse postJson(String url, String json) {
        return request(PageRequest.postJson(url, json));
    }

    public PageResponse request(PageRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request cannot be null");
        }

        String config = request.toJson().toJSONString();
        String javascript =
                "(async function(){"
                        + "var c=" + config + ";"
                        + "var o={method:c.method||'GET',"
                        + "headers:c.headers||{},"
                        + "credentials:c.credentials||'include',"
                        + "redirect:c.redirect||'follow'};"
                        + "if(c.body!==null&&c.body!==undefined"
                        + "&&o.method!=='GET'&&o.method!=='HEAD'){o.body=c.body;}"
                        + "var r=await fetch(c.url,o);"
                        + "var h={};"
                        + "r.headers.forEach(function(v,k){h[k]=v;});"
                        + "var b=await r.text();"
                        + "return {status:r.status,statusText:r.statusText,"
                        + "url:r.url,redirected:r.redirected,headers:h,body:b};"
                        + "})()";

        return PageResponse.fromJson(evalObject(javascript));
    }

    public PageResponse replay(CapturedExchange exchange) {
        return request(
                PageRequest.replay(exchange)
                        .build());
    }

    public PageResponse replay(
            CapturedExchange exchange,
            String replacementBody) {
        return request(
                PageRequest.replay(exchange)
                        .body(replacementBody)
                        .build());
    }

    public void waitForJs(String javascriptCondition, long timeoutMillis) {
        long deadline =
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        RuntimeException last = null;

        while (System.nanoTime() < deadline) {
            try {
                Object value = eval("Boolean(" + javascriptCondition + ")");
                if (Boolean.TRUE.equals(value)) {
                    return;
                }
            } catch (RuntimeException e) {
                last = e;
            }

            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CdpException(
                        "Interrupted while waiting for page condition", e);
            }
        }

        if (last != null) {
            throw new CdpException(
                    "Timed out waiting for page condition: "
                            + javascriptCondition,
                    last);
        }
        throw new CdpException(
                "Timed out waiting for page condition: " + javascriptCondition);
    }

    public void waitForSelector(String cssSelector, long timeoutMillis) {
        String selector = JSON.toJSONString(cssSelector);
        waitForJs(
                "document.querySelector(" + selector + ") !== null",
                timeoutMillis);
    }
}
