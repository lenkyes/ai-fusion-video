package com.stonewu.fusion.service.template;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.entity.template.VideoTemplate;
import com.stonewu.fusion.mapper.template.VideoTemplateMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
public class VideoTemplateService {
    private final VideoTemplateMapper mapper;
    private final ObjectMapper objectMapper;

    public List<VideoTemplate> list(boolean publishedOnly) {
        LambdaQueryWrapper<VideoTemplate> query = new LambdaQueryWrapper<VideoTemplate>()
                .eq(publishedOnly, VideoTemplate::getStatus, 1)
                .orderByAsc(VideoTemplate::getSortOrder).orderByDesc(VideoTemplate::getId);
        return mapper.selectList(query);
    }

    public VideoTemplate get(Long id) {
        VideoTemplate template = mapper.selectById(id);
        if (template == null) throw new BusinessException(404, "视频模板不存在: " + id);
        return template;
    }

    public VideoTemplate create(VideoTemplate template, Long userId) {
        validate(template, false);
        template.setId(null); template.setCreatedBy(userId); template.setDeleted(false);
        template.setVersion(1); if (template.getStatus() == null) template.setStatus(0);
        if (template.getSortOrder() == null) template.setSortOrder(0);
        mapper.insert(template); return template;
    }

    public VideoTemplate update(Long id, VideoTemplate input) {
        VideoTemplate current = get(id);
        validate(input, true);
        current.setName(input.getName()); current.setCategory(input.getCategory());
        current.setDescription(input.getDescription()); current.setCoverUrl(input.getCoverUrl());
        current.setConfigJson(input.getConfigJson()); current.setStatus(input.getStatus());
        current.setSortOrder(input.getSortOrder()); current.setVersion(current.getVersion() + 1);
        mapper.updateById(current); return current;
    }

    public void delete(Long id) { get(id); mapper.deleteById(id); }

    private void validate(VideoTemplate value, boolean update) {
        if (!update && !StringUtils.hasText(value.getCode())) throw new BusinessException("模板编码不能为空");
        if (!StringUtils.hasText(value.getName())) throw new BusinessException("模板名称不能为空");
        if (value.getStatus() != null && (value.getStatus() < 0 || value.getStatus() > 2))
            throw new BusinessException("模板状态无效");
        if (!StringUtils.hasText(value.getConfigJson()) || value.getConfigJson().length() > 100_000)
            throw new BusinessException("模板配置不能为空且不能超过 100KB");
        try { objectMapper.readTree(value.getConfigJson()); }
        catch (Exception e) { throw new BusinessException("模板配置不是有效 JSON"); }
    }
}
