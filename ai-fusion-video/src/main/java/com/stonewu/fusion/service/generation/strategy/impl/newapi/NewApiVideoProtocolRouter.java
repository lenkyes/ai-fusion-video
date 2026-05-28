package com.stonewu.fusion.service.generation.strategy.impl.newapi;

import com.stonewu.fusion.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NewAPI 视频协议路由器。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NewApiVideoProtocolRouter {

    private final List<NewApiVideoProtocolAdapter> adapters;

    private volatile Map<String, NewApiVideoProtocolAdapter> adapterMap;

    public NewApiVideoProtocolAdapter resolve(NewApiVideoProtocolContext context) {
        Map<String, NewApiVideoProtocolAdapter> map = getAdapterMap();
        String protocol = context.metadata() != null ? context.metadata().effectiveProtocol() : "generic";
        String family = context.metadata() != null ? context.metadata().effectiveFamily() : "generic";

        if ("generic".equals(protocol)) {
            NewApiVideoProtocolAdapter familyAdapter = resolveFamilyAdapter(map, family);
            if (familyAdapter != null) {
                return familyAdapter;
            }
        }

        NewApiVideoProtocolAdapter adapter = map.get(protocol);
        if (adapter != null) {
            return adapter;
        }

        NewApiVideoProtocolAdapter familyAdapter = resolveFamilyAdapter(map, family);
        if (familyAdapter != null) {
            log.warn("[NewApi Video] 未找到协议适配器，回退到模型家族适配器: protocol={}, family={}, model={}",
                    protocol, family, context.model() != null ? context.model().getCode() : null);
            return familyAdapter;
        }

        if (!"generic".equals(protocol)) {
            log.warn("[NewApi Video] 未找到协议适配器，回退到通用协议: protocol={}, model={}",
                    protocol, context.model() != null ? context.model().getCode() : null);
        }

        adapter = map.get("generic");
        if (adapter != null) {
            return adapter;
        }

        throw new BusinessException("New API 缺少通用视频协议适配器");
    }

    private NewApiVideoProtocolAdapter resolveFamilyAdapter(Map<String, NewApiVideoProtocolAdapter> map, String family) {
        if ("generic".equals(family)) {
            return null;
        }
        return map.get(family);
    }

    private Map<String, NewApiVideoProtocolAdapter> getAdapterMap() {
        if (adapterMap == null) {
            synchronized (this) {
                if (adapterMap == null) {
                    Map<String, NewApiVideoProtocolAdapter> resolved = new LinkedHashMap<>();
                    for (NewApiVideoProtocolAdapter adapter : adapters) {
                        resolved.putIfAbsent(adapter.getProtocol(), adapter);
                    }
                    adapterMap = resolved;
                }
            }
        }
        return adapterMap;
    }
}
