package com.stonewu.fusion.entity.generation;
import com.baomidou.mybatisplus.annotation.*;
import com.stonewu.fusion.common.BaseEntity;
import lombok.*;
@Data @Builder @NoArgsConstructor @AllArgsConstructor @EqualsAndHashCode(callSuper=true)
@TableName("afv_image_generation_session")
public class ImageGenerationSession extends BaseEntity { @TableId(type=IdType.AUTO) private Long id; private Long userId; private String title; }
