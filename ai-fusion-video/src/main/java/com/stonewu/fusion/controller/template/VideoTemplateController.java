package com.stonewu.fusion.controller.template;

import com.stonewu.fusion.common.CommonResult;
import com.stonewu.fusion.entity.template.VideoTemplate;
import com.stonewu.fusion.service.template.VideoTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;

import static com.stonewu.fusion.security.SecurityUtils.requireCurrentUserId;

@RestController
@RequestMapping("/api/video-template")
@RequiredArgsConstructor
public class VideoTemplateController {
    private final VideoTemplateService service;

    @GetMapping("/list")
    @PreAuthorize("#publishedOnly or hasRole('ADMIN')")
    public CommonResult<List<VideoTemplate>> list(@RequestParam(defaultValue = "true") boolean publishedOnly) {
        return CommonResult.success(service.list(publishedOnly));
    }
    @GetMapping("/{id}") public CommonResult<VideoTemplate> get(@PathVariable Long id) { return CommonResult.success(service.get(id)); }
    @PostMapping @PreAuthorize("hasRole('ADMIN')") public CommonResult<VideoTemplate> create(@RequestBody VideoTemplate value) { return CommonResult.success(service.create(value, requireCurrentUserId())); }
    @PutMapping("/{id}") @PreAuthorize("hasRole('ADMIN')") public CommonResult<VideoTemplate> update(@PathVariable Long id, @RequestBody VideoTemplate value) { return CommonResult.success(service.update(id, value)); }
    @DeleteMapping("/{id}") @PreAuthorize("hasRole('ADMIN')") public CommonResult<Boolean> delete(@PathVariable Long id) { service.delete(id); return CommonResult.success(true); }
}
