package com.stonewu.fusion.service.storage.strategy;

import com.stonewu.fusion.entity.storage.StorageConfig;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class S3StorageStrategyTests {

    private final S3StorageStrategy strategy = new S3StorageStrategy();

    @Test
    void buildAccessUrlUsesPathStyleEndpoint() {
        StorageConfig config = StorageConfig.builder()
                .endpoint("https://minio-srv.701111.xyz")
                .bucketName("ace")
                .build();

        assertThat(buildAccessUrl(config, "fusion/images/image.png"))
                .isEqualTo("https://minio-srv.701111.xyz/ace/fusion/images/image.png");
    }

    @Test
    void buildAccessUrlNormalizesEndpointSchemeAndTrailingSlash() {
        StorageConfig config = StorageConfig.builder()
                .endpoint("minio.example.com/")
                .bucketName("assets")
                .build();

        assertThat(buildAccessUrl(config, "videos/video.mp4"))
                .isEqualTo("https://minio.example.com/assets/videos/video.mp4");
    }

    @Test
    void buildAccessUrlKeepsCustomDomainBehavior() {
        StorageConfig config = StorageConfig.builder()
                .endpoint("https://minio.example.com")
                .bucketName("assets")
                .customDomain("https://cdn.example.com/")
                .build();

        assertThat(buildAccessUrl(config, "images/image.png"))
                .isEqualTo("https://cdn.example.com/images/image.png");
    }

    @Test
    void buildAccessUrlIgnoresLegacyBucketSubdomain() {
        StorageConfig config = StorageConfig.builder()
                .endpoint("https://minio-srv.701111.xyz")
                .bucketName("ace")
                .customDomain("https://ace.minio-srv.701111.xyz")
                .build();

        assertThat(buildAccessUrl(config, "fusion/images/image.png"))
                .isEqualTo("https://minio-srv.701111.xyz/ace/fusion/images/image.png");
    }

    private String buildAccessUrl(StorageConfig config, String objectKey) {
        return ReflectionTestUtils.invokeMethod(strategy, "buildAccessUrl", config, objectKey);
    }
}
