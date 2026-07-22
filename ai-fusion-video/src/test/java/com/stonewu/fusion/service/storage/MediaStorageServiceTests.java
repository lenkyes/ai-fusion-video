package com.stonewu.fusion.service.storage;

import com.stonewu.fusion.entity.storage.StorageConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class MediaStorageServiceTests {

    @Test
    void storeBytesResolvesTheCurrentDefaultStorageForEveryWrite() {
        StorageConfigService configService = mock(StorageConfigService.class);
        StorageStrategy local = mock(StorageStrategy.class);
        StorageStrategy s3 = mock(StorageStrategy.class);
        when(local.getType()).thenReturn("local");
        when(s3.getType()).thenReturn("s3");
        StorageConfig localConfig = StorageConfig.builder().type("local").build();
        StorageConfig s3Config = StorageConfig.builder().type("s3").build();
        when(configService.getDefaultConfig()).thenReturn(localConfig, s3Config);
        when(local.storeBytes(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.same(localConfig)))
                .thenReturn("/media/images/first.png");
        when(s3.storeBytes(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.same(s3Config)))
                .thenReturn("https://minio.example/ace/images/second.png");
        MediaStorageService service = new MediaStorageService(configService, List.of(local, s3));

        assertThat(service.storeBytes(new byte[]{1}, "images", "png"))
                .isEqualTo("/media/images/first.png");
        assertThat(service.storeBytes(new byte[]{2}, "images", "png"))
                .isEqualTo("https://minio.example/ace/images/second.png");
        verify(configService, times(2)).getDefaultConfig();
    }

    @Test
    void downloadAndStoreRewritesLegacyMinioUrlWithoutUploadingAgain() {
        StorageConfigService configService = mock(StorageConfigService.class);
        StorageStrategy strategy = mock(StorageStrategy.class);
        when(strategy.getType()).thenReturn("s3");
        when(configService.getDefaultConfig()).thenReturn(StorageConfig.builder()
                .type("s3")
                .endpoint("https://minio-srv.701111.xyz")
                .bucketName("ace")
                .build());
        MediaStorageService service = new MediaStorageService(configService, List.of(strategy));

        String result = service.downloadAndStore(
                "https://ace.minio-srv.701111.xyz/fusion/images/image.png", "images");

        assertThat(result).isEqualTo(
                "https://minio-srv.701111.xyz/ace/fusion/images/image.png");
        verify(strategy, never()).store(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }
}
