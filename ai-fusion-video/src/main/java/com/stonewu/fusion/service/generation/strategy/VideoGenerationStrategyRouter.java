package com.stonewu.fusion.service.generation.strategy;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.entity.ai.AiModel;
import com.stonewu.fusion.service.ai.model.AiModelMetadata;
import com.stonewu.fusion.service.ai.model.AiModelMetadataResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 视频生成策略路由器。
 * <p>
 * 顶层始终按接入渠道选择策略，避免模型 code 与渠道语义耦合。
 */
@Component
@RequiredArgsConstructor
public class VideoGenerationStrategyRouter {

    private final List<VideoGenerationStrategy> strategies;
    private final AiModelMetadataResolver aiModelMetadataResolver;

    private volatile Map<String, VideoGenerationStrategy> strategyMap;

    public VideoGenerationStrategy resolve(AiModel model) {
        if (model == null) {
            throw new BusinessException("视频模型不存在");
        }

        Map<String, VideoGenerationStrategy> candidates = getStrategyMap();
        if (candidates.isEmpty()) {
            throw new BusinessException("没有可用的视频生成策略");
        }

        AiModelMetadata metadata = resolveMetadata(model);
        if (metadata == null || StrUtil.isBlank(metadata.normalizedPlatform())) {
            throw new BusinessException("视频模型未绑定有效 API 配置，无法确定接入渠道");
        }

        String strategyKey = resolveStrategyKey(model, metadata, candidates);
        VideoGenerationStrategy strategy = candidates.get(strategyKey);
        if (strategy != null) {
            return strategy;
        }

        throw new BusinessException("未找到匹配的视频生成策略: " + metadata.platform());
    }

    public boolean supports(AiModel model) {
        if (model == null) {
            return false;
        }
        try {
            AiModelMetadata metadata = resolveMetadata(model);
            if (metadata == null || StrUtil.isBlank(metadata.normalizedPlatform())) {
                return false;
            }
            return getStrategyMap().containsKey(resolveStrategyKey(model, metadata, getStrategyMap()));
        } catch (Exception ignored) {
            return false;
        }
    }

    public String supportedPlatformsText() {
        String text = String.join(", ", getStrategyMap().keySet());
        if (getStrategyMap().containsKey("newapi")) {
            text += ", openai_compatible(seedance), bytedance(seedance)";
        }
        return text;
    }

    private AiModelMetadata resolveMetadata(AiModel model) {
        String platform = aiModelMetadataResolver.resolvePlatform(model);
        return aiModelMetadataResolver.resolve(model, platform);
    }

    private String resolveStrategyKey(AiModel model, AiModelMetadata metadata,
                                      Map<String, VideoGenerationStrategy> candidates) {
        String explicitStrategy = configuredStrategy(model);
        if (StrUtil.isNotBlank(explicitStrategy)) {
            return aiModelMetadataResolver.normalizePlatform(explicitStrategy);
        }

        String normalizedPlatform = metadata.normalizedPlatform();
        if (candidates.containsKey(normalizedPlatform)) {
            return normalizedPlatform;
        }

        if (isOpenAiCompatibleSeedanceGateway(metadata) && candidates.containsKey("newapi")) {
            return "newapi";
        }
        return normalizedPlatform;
    }

    private boolean isOpenAiCompatibleSeedanceGateway(AiModelMetadata metadata) {
        if (metadata == null || !"seedance".equals(metadata.effectiveFamily())) {
            return false;
        }
        String platform = metadata.normalizedPlatform();
        return "openai_compatible".equals(platform) || "bytedance".equals(platform);
    }

    private String configuredStrategy(AiModel model) {
        JSONObject config = parseConfig(model != null ? model.getConfig() : null);
        return firstNonBlank(
                getString(config, "videoStrategy", "video_strategy",
                        "videoStrategyPlatform", "video_strategy_platform"),
                getString(config, "generationStrategy", "generation_strategy",
                        "generationStrategyPlatform", "generation_strategy_platform"),
                getString(config, "strategyPlatform", "strategy_platform", "strategy"));
    }

    private JSONObject parseConfig(String configJson) {
        if (StrUtil.isBlank(configJson)) {
            return new JSONObject();
        }
        try {
            return JSONUtil.parseObj(configJson);
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private String getString(JSONObject config, String... keys) {
        if (config == null) {
            return null;
        }
        for (String key : keys) {
            Object value = config.get(key);
            if (value == null) {
                continue;
            }
            String text = value.toString().trim();
            if (StrUtil.isNotBlank(text)) {
                return text;
            }
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StrUtil.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private Map<String, VideoGenerationStrategy> getStrategyMap() {
        if (strategyMap == null) {
            synchronized (this) {
                if (strategyMap == null) {
                    Map<String, VideoGenerationStrategy> map = new LinkedHashMap<>();
                    for (VideoGenerationStrategy strategy : strategies) {
                        map.putIfAbsent(normalizeStrategyName(strategy.getName()), strategy);
                    }
                    strategyMap = map;
                }
            }
        }
        return strategyMap;
    }

    private String normalizeStrategyName(String name) {
        String normalized = aiModelMetadataResolver.normalizePlatform(name);
        return StrUtil.isBlank(normalized) ? "" : normalized;
    }
}
