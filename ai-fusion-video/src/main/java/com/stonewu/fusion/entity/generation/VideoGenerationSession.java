package com.stonewu.fusion.entity.generation;
import com.baomidou.mybatisplus.annotation.*;
import com.stonewu.fusion.common.BaseEntity;
import lombok.*;
@Data @Builder @NoArgsConstructor @AllArgsConstructor @EqualsAndHashCode(callSuper=true)
@TableName("afv_video_generation_session")
public class VideoGenerationSession extends BaseEntity { @TableId(type=IdType.AUTO) private Long id; private Long userId; private String title; }
