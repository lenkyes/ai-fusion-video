package com.stonewu.fusion.service.generation.strategy.impl.newapi;

import cn.hutool.json.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * NewAPI/兼容网关下的 Seedance Content Generation 协议适配器。
 */
@Component
@RequiredArgsConstructor
public class NewApiSeedanceVideoProtocolAdapter implements NewApiVideoProtocolAdapter {

    private final NewApiVideoProtocolSupport support;

    @Override
    public String getProtocol() {
        return "seedance";
    }

    @Override
    public JSONObject buildSubmitBody(NewApiVideoProtocolContext context) {
        return support.buildSeedanceContentGenerationBody(context);
    }
}
