package com.stonewu.fusion.service.generation.strategy;

import cn.hutool.core.util.StrUtil;
import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.entity.ai.AiModel;
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

        String platform = aiModelMetadataResolver.resolvePlatform(model);
        String normalizedPlatform = aiModelMetadataResolver.normalizePlatform(platform);
        if (StrUtil.isBlank(normalizedPlatform)) {
            throw new BusinessException("视频模型未绑定有效 API 配置，无法确定接入渠道");
        }

        VideoGenerationStrategy strategy = candidates.get(normalizedPlatform);
        if (strategy != null) {
            return strategy;
        }

        throw new BusinessException("未找到匹配的视频生成策略: " + platform);
    }

    public boolean supports(AiModel model) {
        if (model == null) {
            return false;
        }
        try {
            String platform = aiModelMetadataResolver.resolvePlatform(model);
            String normalizedPlatform = aiModelMetadataResolver.normalizePlatform(platform);
            return StrUtil.isNotBlank(normalizedPlatform) && getStrategyMap().containsKey(normalizedPlatform);
        } catch (Exception ignored) {
            return false;
        }
    }

    public String supportedPlatformsText() {
        return String.join(", ", getStrategyMap().keySet());
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
