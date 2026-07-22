package com.stonewu.fusion.service.storage.strategy;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.stonewu.fusion.entity.storage.StorageConfig;
import com.stonewu.fusion.service.storage.StorageStrategy;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * S3 兼容存储策略
 * <p>
 * 使用 AWS S3 SDK v2 操作 S3 兼容的对象存储服务。
 * 兼容阿里云 OSS、腾讯 COS、MinIO、AWS S3 等。
 * <p>
 * StorageConfig 字段映射：
 * - endpoint: S3 兼容端点地址（如 https://oss-cn-hangzhou.aliyuncs.com）
 * - bucketName: 存储桶名称
 * - accessKey: Access Key ID
 * - secretKey: Access Key Secret
 * - region: 区域（如 cn-hangzhou、us-east-1，默认 us-east-1）
 * - basePath: 对象 key 前缀（如 ai-fusion/）
 * - customDomain: 自定义域名（可选，用于替代默认域名生成访问 URL）
 */
@Component
@Slf4j
public class S3StorageStrategy implements StorageStrategy {

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

    @Override
    public String getType() {
        return "s3";
    }

    @Override
    public String store(String remoteUrl, String subDir, StorageConfig config) {
        validateConfig(config);

        Request request = new Request.Builder().url(remoteUrl).build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new RuntimeException("下载文件失败: HTTP " + response.code() + " url=" + remoteUrl);
            }

            String extension = guessExtension(remoteUrl, response.header("Content-Type"));
            String contentType = response.header("Content-Type");
            String objectKey = buildObjectKey(config, subDir, extension);

            byte[] data = response.body().bytes();
            uploadToS3(config, objectKey, data, contentType);

            String accessUrl = buildAccessUrl(config, objectKey);
            log.info("[S3Storage] 文件已上传: key={}, size={} bytes, url={}", objectKey, data.length, accessUrl);
            return accessUrl;
        } catch (IOException e) {
            throw new RuntimeException("S3 存储失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String storeBytes(byte[] data, String subDir, String extension, StorageConfig config) {
        validateConfig(config);

        String objectKey = buildObjectKey(config, subDir, extension);
        String contentType = guessContentType(extension);
        uploadToS3(config, objectKey, data, contentType);

        String accessUrl = buildAccessUrl(config, objectKey);
        log.info("[S3Storage] 文件已上传: key={}, size={} bytes, url={}", objectKey, data.length, accessUrl);
        return accessUrl;
    }

    @Override
    public String storeFile(Path filePath, String subDir, String extension, StorageConfig config) {
        validateConfig(config);

        String objectKey = buildObjectKey(config, subDir, extension);
        String contentType = guessContentType(extension);
        uploadFileToS3(config, objectKey, filePath, contentType);

        String accessUrl = buildAccessUrl(config, objectKey);
        try {
            log.info("[S3Storage] 文件已上传: key={}, size={} bytes, url={}",
                    objectKey, Files.size(filePath), accessUrl);
        } catch (IOException e) {
            log.info("[S3Storage] 文件已上传: key={}, url={}", objectKey, accessUrl);
        }
        return accessUrl;
    }

    private void uploadToS3(StorageConfig config, String objectKey, byte[] data, String contentType) {
        S3Client s3 = buildS3Client(config);
        try {
            PutObjectRequest.Builder putBuilder = PutObjectRequest.builder()
                    .bucket(config.getBucketName())
                    .key(objectKey);
            if (StrUtil.isNotBlank(contentType)) {
                putBuilder.contentType(contentType);
            }
            s3.putObject(putBuilder.build(), RequestBody.fromBytes(data));
        } finally {
            s3.close();
        }
    }

    private void uploadFileToS3(StorageConfig config, String objectKey, Path filePath, String contentType) {
        S3Client s3 = buildS3Client(config);
        try {
            PutObjectRequest.Builder putBuilder = PutObjectRequest.builder()
                    .bucket(config.getBucketName())
                    .key(objectKey);
            if (StrUtil.isNotBlank(contentType)) {
                putBuilder.contentType(contentType);
            }
            s3.putObject(putBuilder.build(), RequestBody.fromFile(filePath));
        } finally {
            s3.close();
        }
    }

    private S3Client buildS3Client(StorageConfig config) {
        AwsBasicCredentials credentials = AwsBasicCredentials.create(
                config.getAccessKey(), config.getSecretKey());

        String region = StrUtil.blankToDefault(config.getRegion(), "us-east-1");

        return S3Client.builder()
                .endpointOverride(URI.create(normalizeEndpoint(config.getEndpoint())))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .serviceConfiguration(S3Configuration.builder()
      // Use bucket-host API routing; public URLs are normalized to path-style below.
      .pathStyleAccessEnabled(false)
                        .chunkedEncodingEnabled(false)  // 兼容部分 S3 兼容服务
                        .build())
                .build();
    }

    private String buildObjectKey(StorageConfig config, String subDir, String extension) {
        String prefix = StrUtil.isNotBlank(config.getBasePath())
                ? config.getBasePath().replaceAll("^/+|/+$", "") + "/"
                : "";
        return prefix + subDir + "/" + IdUtil.fastSimpleUUID() + "." + extension;
    }

    private String buildAccessUrl(StorageConfig config, String objectKey) {
        // 优先使用自定义域名
        if (StrUtil.isNotBlank(config.getCustomDomain()) && !isLegacyBucketDomain(config)) {
            String domain = config.getCustomDomain().replaceAll("/+$", "");
            if (!domain.startsWith("http://") && !domain.startsWith("https://")) {
                domain = "https://" + domain;
            }
            return domain + "/" + objectKey;
        }

        // 默认拼接 endpoint + bucket
        // The S3 client uses path-style access, so its public URL must use the
        // same endpoint/bucket/object layout (required by MinIO, among others).
        String endpoint = normalizeEndpoint(config.getEndpoint()).replaceAll("/+$", "");
        return endpoint + "/" + config.getBucketName() + "/" + objectKey;
    }

    private boolean isLegacyBucketDomain(StorageConfig config) {
        String endpoint = normalizeEndpoint(config.getEndpoint()).replaceAll("/+$", "");
        String legacyDomain = endpoint.replaceFirst("^(https?://)", "$1" + config.getBucketName() + ".");
        String customDomain = normalizeEndpoint(config.getCustomDomain()).replaceAll("/+$", "");
        return customDomain.equalsIgnoreCase(legacyDomain);
    }

    private String normalizeEndpoint(String endpoint) {
        if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
            return "https://" + endpoint;
        }
        return endpoint;
    }

    private void validateConfig(StorageConfig config) {
        if (config == null) {
            throw new RuntimeException("S3 存储配置为空");
        }
        if (StrUtil.isBlank(config.getEndpoint())) {
            throw new RuntimeException("S3 存储配置缺少 endpoint");
        }
        if (StrUtil.isBlank(config.getBucketName())) {
            throw new RuntimeException("S3 存储配置缺少 bucketName");
        }
        if (StrUtil.isBlank(config.getAccessKey()) || StrUtil.isBlank(config.getSecretKey())) {
            throw new RuntimeException("S3 存储配置缺少 accessKey 或 secretKey");
        }
    }

    /**
     * 推断文件扩展名
     */
    private String guessExtension(String url, String contentType) {
        if (StrUtil.isNotBlank(contentType)) {
            String lower = contentType.toLowerCase();
            if (lower.contains("png")) return "png";
            if (lower.contains("jpeg") || lower.contains("jpg")) return "jpg";
            if (lower.contains("gif")) return "gif";
            if (lower.contains("webp")) return "webp";
            if (lower.contains("mp4")) return "mp4";
            if (lower.contains("webm")) return "webm";
            if (lower.contains("mov") || lower.contains("quicktime")) return "mov";
        }
        try {
            String path = url.split("\\?")[0];
            int dotIdx = path.lastIndexOf('.');
            if (dotIdx > 0) {
                String ext = path.substring(dotIdx + 1).toLowerCase();
                if (ext.length() <= 5) return ext;
            }
        } catch (Exception ignored) {
        }
        return "bin";
    }

    private String guessContentType(String extension) {
        return switch (extension.toLowerCase()) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "svg" -> "image/svg+xml";
            case "mp4" -> "video/mp4";
            case "webm" -> "video/webm";
            case "mov" -> "video/quicktime";
            default -> "application/octet-stream";
        };
    }
}
