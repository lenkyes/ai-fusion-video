package com.stonewu.fusion.service.storage;

import cn.hutool.core.util.StrUtil;
import com.stonewu.fusion.entity.storage.StorageConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 媒体存储门面服务
 * <p>
 * 根据当前默认存储配置自动选择对应的存储策略，
 * 提供统一的文件下载 & 持久化接口。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MediaStorageService {

    private final StorageConfigService storageConfigService;
    private final List<StorageStrategy> strategies;

    private Map<String, StorageStrategy> strategyMap;

    private Map<String, StorageStrategy> getStrategyMap() {
        if (strategyMap == null) {
            strategyMap = strategies.stream()
                    .collect(Collectors.toMap(StorageStrategy::getType, s -> s));
        }
        return strategyMap;
    }

    /**
     * 从远程 URL 下载文件并保存到默认存储后端
     *
     * @param remoteUrl 远程文件 URL
     * @param subDir    子目录（如 images、videos）
     * @return 持久化后的可访问 URL
     */
    public String downloadAndStore(String remoteUrl, String subDir) {
        if (StrUtil.isBlank(remoteUrl)) {
            return remoteUrl;
        }
        // 已经是本地路径（/media/...）则跳过
        if (remoteUrl.startsWith("/media/")) {
            return remoteUrl;
        }

        StorageConfig config = storageConfigService.getDefaultConfig();
        StorageStrategy strategy = resolveStrategy(config);

        String storedUrl = normalizeExistingS3Url(remoteUrl, config);
        if (storedUrl != null) {
            return storedUrl;
        }

        log.info("[MediaStorage] 开始持久化: url={}, subDir={}, strategy={}",
                remoteUrl, subDir, strategy.getType());
        return strategy.store(remoteUrl, subDir, config);
    }

    private String normalizeExistingS3Url(String remoteUrl, StorageConfig config) {
        if (config == null || !"s3".equalsIgnoreCase(config.getType())
                || StrUtil.isBlank(config.getEndpoint()) || StrUtil.isBlank(config.getBucketName())) {
            return null;
        }

        String endpoint = config.getEndpoint();
        if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
            endpoint = "https://" + endpoint;
        }
        endpoint = endpoint.replaceAll("/+$", "");
        String canonicalPrefix = endpoint + "/" + config.getBucketName() + "/";
        if (remoteUrl.startsWith(canonicalPrefix)) {
            return remoteUrl;
        }

        String legacyPrefix = endpoint.replaceFirst(
                "^(https?://)", "$1" + config.getBucketName() + ".") + "/";
        if (remoteUrl.regionMatches(true, 0, legacyPrefix, 0, legacyPrefix.length())) {
            return canonicalPrefix + remoteUrl.substring(legacyPrefix.length());
        }
        return null;
    }

    /**
     * 从字节数组保存文件到默认存储后端
     *
     * @param data      文件字节数据
     * @param subDir    子目录
     * @param extension 文件扩展名
     * @return 持久化后的可访问 URL
     */
    public String storeBytes(byte[] data, String subDir, String extension) {
        StorageConfig config = storageConfigService.getDefaultConfig();
        StorageStrategy strategy = resolveStrategy(config);

        log.info("[MediaStorage] 直接保存字节: size={}, subDir={}, ext={}, strategy={}",
                data.length, subDir, extension, strategy.getType());
        return strategy.storeBytes(data, subDir, extension, config);
    }

    /**
     * 从本地文件保存到默认存储后端
     *
     * @param filePath   本地文件路径
     * @param subDir     子目录
     * @param extension  文件扩展名
     * @return 持久化后的可访问 URL
     */
    public String storeFile(Path filePath, String subDir, String extension) {
        StorageConfig config = storageConfigService.getDefaultConfig();
        StorageStrategy strategy = resolveStrategy(config);

        log.info("[MediaStorage] 直接保存文件: path={}, subDir={}, ext={}, strategy={}",
                filePath, subDir, extension, strategy.getType());
        return strategy.storeFile(filePath, subDir, extension, config);
    }

    private StorageStrategy resolveStrategy(StorageConfig config) {
        Map<String, StorageStrategy> map = getStrategyMap();

        if (config != null && StrUtil.isNotBlank(config.getType())) {
            StorageStrategy strategy = map.get(config.getType());
            if (strategy != null) {
                return strategy;
            }
            log.warn("[MediaStorage] 未找到类型 {} 对应的策略，回退到 local", config.getType());
        }

        // 回退到本地存储
        StorageStrategy local = map.get("local");
        if (local == null) {
            throw new RuntimeException("没有可用的存储策略");
        }
        return local;
    }
}
