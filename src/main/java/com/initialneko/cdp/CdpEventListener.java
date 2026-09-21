package com.initialneko.cdp;

import com.alibaba.fastjson.JSONObject;

public interface CdpEventListener {
    void onEvent(String method, JSONObject params);
}
