package com.stonewu.fusion.service.system;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.fusion.entity.system.SystemConfig;
import com.stonewu.fusion.mapper.system.SystemConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.List;

/**
 * 系统配置服务
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SystemConfigService {

    private final SystemConfigMapper systemConfigMapper;
    private final PresetArtStyleResourceResolver presetArtStyleResourceResolver;

    /**
     * 获取配置值
     */
    @Cacheable(value = "systemConfig", key = "#key", unless = "#result == null")
    public String getValue(String key) {
        SystemConfig config = systemConfigMapper.selectOne(
                new LambdaQueryWrapper<SystemConfig>()
                        .eq(SystemConfig::getConfigKey, key)
                        .last("LIMIT 1"));
        return config != null ? config.getConfigValue() : null;
    }

    /**
     * 设置配置值（不存在则创建）
     */
    @CacheEvict(value = "systemConfig", key = "#key")
    public void setValue(String key, String value) {
        SystemConfig existing = systemConfigMapper.selectOne(
                new LambdaQueryWrapper<SystemConfig>()
                        .eq(SystemConfig::getConfigKey, key)
                        .last("LIMIT 1"));
        if (existing != null) {
            existing.setConfigValue(value);
            systemConfigMapper.updateById(existing);
        } else {
            SystemConfig config = SystemConfig.builder()
                    .configKey(key)
                    .configValue(value)
                    .build();
            systemConfigMapper.insert(config);
        }
    }

    /**
     * 获取所有配置
     */
    public List<SystemConfig> getAll() {
        return systemConfigMapper.selectList(
                new LambdaQueryWrapper<SystemConfig>()
                        .orderByAsc(SystemConfig::getConfigKey));
    }

    /**
     * 获取站点访问域名
     */
    public String getSiteBaseUrl() {
        return normalizeBaseUrl(getValue("site_base_url"));
    }

    /**
     * 获取对外可访问的资源域名。
     * <p>
     * 前后端分域部署时，API 域名未必能访问 /media/** 静态资源，因此优先使用 asset_public_base_url。
     */
    public String getAssetPublicBaseUrl() {
        String url = getValue("asset_public_base_url");
        if (StrUtil.isBlank(url)) {
            url = getValue("media_public_base_url");
        }
        if (StrUtil.isBlank(url)) {
            url = getSiteBaseUrl();
        }
        return normalizeBaseUrl(url);
    }

    /**
     * 获取布尔配置值。
     */
    public boolean getBooleanValue(String key, boolean defaultValue) {
        String value = getValue(key);
        if (StrUtil.isBlank(value)) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }

    /**
     * 是否允许公开注册。
     */
    public boolean isRegistrationEnabled() {
        return getBooleanValue("allow_register", false);
    }

    /**
     * 设置是否允许公开注册。
     */
    public void setRegistrationEnabled(boolean enabled) {
        setValue("allow_register", Boolean.toString(enabled));
    }

    /**
     * 将相对路径解析为完整的公网可访问 URL
     * <p>
     * 1. 已是完整 URL (http/https) → OSS/CDN 直链直接返回，内部 /media/** 或 /api/art-styles/** 可按资源域名重写
     * 2. 预设画风图 (/art-styles/** 或 /api/art-styles/**) 统一映射到后端静态资源端点 /api/art-styles/**
     * 3. 其他相对路径优先用 asset_public_base_url/media_public_base_url 拼接，未配置时回退 site_base_url
     * 4. 没有公网资源域名时，预设画风图返回相对 API 路径，兼容本地直连后端
     */
    public String resolvePublicUrl(String relativePath) {
        if (StrUtil.isBlank(relativePath)) {
            return null;
        }
        // 已经是完整 URL（如 OSS 直链）；内部静态资源 URL 可重写到资源公网域名。
        if (relativePath.startsWith("http://") || relativePath.startsWith("https://")) {
            return rewriteInternalPublicUrl(relativePath);
        }
        String normalizedPath = relativePath.startsWith("/") ? relativePath : "/" + relativePath;
        if (presetArtStyleResourceResolver.isPresetArtStylePath(normalizedPath)) {
            String apiPath = presetArtStyleResourceResolver.toApiPath(normalizedPath);
            if (StrUtil.isBlank(apiPath)) {
                return normalizedPath;
            }
            String assetPublicBaseUrl = getAssetPublicBaseUrl();
            if (StrUtil.isBlank(assetPublicBaseUrl)) {
                return apiPath;
            }
            return buildApiUrl(assetPublicBaseUrl, apiPath);
        }
        // 拼接资源公网域名
        String assetPublicBaseUrl = getAssetPublicBaseUrl();
        if (StrUtil.isNotBlank(assetPublicBaseUrl)) {
            return assetPublicBaseUrl + normalizedPath;
        }
        return null;
    }

    private String rewriteInternalPublicUrl(String url) {
        String assetPublicBaseUrl = getAssetPublicBaseUrl();
        if (StrUtil.isBlank(assetPublicBaseUrl)) {
            return url;
        }
        try {
            URI uri = URI.create(url);
            String path = uri.getRawPath();
            if (!isInternalPublicPath(path)) {
                return url;
            }
            StringBuilder rebuilt = new StringBuilder(buildApiUrl(assetPublicBaseUrl, path));
            if (StrUtil.isNotBlank(uri.getRawQuery())) {
                rebuilt.append('?').append(uri.getRawQuery());
            }
            if (StrUtil.isNotBlank(uri.getRawFragment())) {
                rebuilt.append('#').append(uri.getRawFragment());
            }
            return rebuilt.toString();
        } catch (Exception e) {
            return url;
        }
    }

    private boolean isInternalPublicPath(String path) {
        if (StrUtil.isBlank(path)) {
            return false;
        }
        String lower = path.toLowerCase();
        return lower.startsWith("/media/") || lower.startsWith("/api/art-styles/") || lower.startsWith("/art-styles/");
    }

    private String normalizeBaseUrl(String url) {
        if (StrUtil.isBlank(url)) {
            return url;
        }
        return url.replaceAll("/+$", "");
    }

    private String buildApiUrl(String siteBaseUrl, String apiPath) {
        if (StrUtil.isBlank(siteBaseUrl)) {
            return apiPath;
        }
        if (siteBaseUrl.endsWith("/api") && apiPath.startsWith("/api/")) {
            return siteBaseUrl + apiPath.substring("/api".length());
        }
        return siteBaseUrl + apiPath;
    }
}
