package com.stonewu.fusion.controller.storage;

import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.common.CommonResult;
import com.stonewu.fusion.service.storage.MediaStorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * 通用文件上传 Controller
 */
@Tag(name = "文件上传")
@RestController
@RequestMapping("/api/storage")
@RequiredArgsConstructor
@Slf4j
public class FileUploadController {

    private static final long MAX_FILE_SIZE = 100 * 1024 * 1024; // 100MB
    private static final long MAX_AUDIO_FILE_SIZE = 200L * 1024 * 1024;
    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of(
            "image/png", "image/jpeg", "image/jpg", "image/webp", "image/gif"
    );
    private static final Set<String> ALLOWED_AUDIO_EXTENSIONS = Set.of(
            "mp3", "wav", "m4a", "aac", "ogg", "flac"
    );
    private static final Set<String> ALLOWED_AUDIO_TYPES = Set.of(
            "audio/mpeg", "audio/mp3", "audio/wav", "audio/x-wav", "audio/mp4",
            "audio/aac", "audio/ogg", "audio/flac", "audio/x-flac", "application/ogg"
    );

    private final MediaStorageService mediaStorageService;

    @PostMapping("/upload")
    @Operation(summary = "上传文件")
    public CommonResult<String> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "subDir", defaultValue = "uploads") String subDir) {

        if (file.isEmpty()) {
            throw new BusinessException("文件不能为空");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException("文件大小不能超过 100MB");
        }

        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_IMAGE_TYPES.contains(contentType.toLowerCase())) {
            throw new BusinessException("仅支持图片格式：PNG, JPEG, WebP, GIF");
        }

        try {
            String ext = getExtension(file.getOriginalFilename());
            String url = mediaStorageService.storeBytes(file.getBytes(), subDir, ext);
            log.info("[FileUpload] 上传成功: size={}KB, url={}", file.getSize() / 1024, url);
            return CommonResult.success(url);
        } catch (IOException e) {
            log.error("[FileUpload] 上传失败", e);
            throw new BusinessException("上传失败: " + e.getMessage());
        }
    }

    @PostMapping("/upload-audio")
    @Operation(summary = "上传剪辑背景音乐")
    public CommonResult<String> uploadAudio(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("音频文件不能为空");
        }
        if (file.getSize() > MAX_AUDIO_FILE_SIZE) {
            throw new BusinessException("音频文件大小不能超过 200MB");
        }
        String extension = getExtension(file.getOriginalFilename()).toLowerCase(Locale.ROOT);
        String contentType = file.getContentType() != null
                ? file.getContentType().toLowerCase(Locale.ROOT) : "";
        if (!ALLOWED_AUDIO_EXTENSIONS.contains(extension)
                || (!contentType.isBlank() && !ALLOWED_AUDIO_TYPES.contains(contentType))) {
            throw new BusinessException("仅支持 MP3、WAV、M4A、AAC、OGG、FLAC 音频");
        }

        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("bgm_upload_", "." + extension);
            file.transferTo(tempFile);
            String url = mediaStorageService.storeFile(tempFile, "audio/bgm", extension);
            log.info("[FileUpload] BGM 上传成功: size={}KB, url={}", file.getSize() / 1024, url);
            return CommonResult.success(url);
        } catch (IOException e) {
            log.error("[FileUpload] BGM 上传失败", e);
            throw new BusinessException("音频上传失败: " + e.getMessage());
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    log.warn("[FileUpload] 临时音频清理失败: {}", tempFile, e);
                }
            }
        }
    }

    private String getExtension(String filename) {
        if (filename == null) return "png";
        int dotIndex = filename.lastIndexOf('.');
        return dotIndex >= 0 ? filename.substring(dotIndex + 1) : "png";
    }
}
