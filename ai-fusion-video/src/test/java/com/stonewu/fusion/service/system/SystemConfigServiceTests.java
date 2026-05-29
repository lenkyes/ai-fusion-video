package com.stonewu.fusion.service.system;

import com.stonewu.fusion.entity.system.SystemConfig;
import com.stonewu.fusion.mapper.system.SystemConfigMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SystemConfigServiceTests {

    @Test
    void resolvePublicUrlUsesAssetPublicBaseUrlForRelativeMediaPath() {
        SystemConfigMapper mapper = mock(SystemConfigMapper.class);
        when(mapper.selectOne(any())).thenReturn(config("https://fusion.example.com/"));

        SystemConfigService service = new SystemConfigService(mapper, mock(PresetArtStyleResourceResolver.class));

        assertThat(service.resolvePublicUrl("/media/images/a.png"))
                .isEqualTo("https://fusion.example.com/media/images/a.png");
    }

    @Test
    void resolvePublicUrlRewritesAbsoluteInternalMediaUrlToAssetPublicBaseUrl() {
        SystemConfigMapper mapper = mock(SystemConfigMapper.class);
        when(mapper.selectOne(any())).thenReturn(config("https://fusion.example.com"));

        SystemConfigService service = new SystemConfigService(mapper, mock(PresetArtStyleResourceResolver.class));

        assertThat(service.resolvePublicUrl("https://api.example.com/media/images/a.png?x=1"))
                .isEqualTo("https://fusion.example.com/media/images/a.png?x=1");
    }

    @Test
    void resolvePublicUrlFallsBackToSiteBaseUrlWhenAssetBaseUrlIsMissing() {
        SystemConfigMapper mapper = mock(SystemConfigMapper.class);
        when(mapper.selectOne(any()))
                .thenReturn(null)
                .thenReturn(null)
                .thenReturn(config("https://fusion.example.com/"));

        SystemConfigService service = new SystemConfigService(mapper, mock(PresetArtStyleResourceResolver.class));

        assertThat(service.resolvePublicUrl("/media/images/a.png"))
                .isEqualTo("https://fusion.example.com/media/images/a.png");
    }

    private SystemConfig config(String value) {
        return SystemConfig.builder().configValue(value).build();
    }
}
