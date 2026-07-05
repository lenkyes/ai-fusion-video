package com.stonewu.fusion.entity.storyboard;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.stonewu.fusion.common.BaseEntity;
import com.stonewu.fusion.common.handler.JsonbTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Storyboard video quality review result.
 */
@TableName(value = "afv_storyboard_video_quality", autoResultMap = true)
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StoryboardVideoQuality extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long storyboardItemId;

    private Long videoTaskId;

    private Long videoItemId;

    private String videoUrl;

    private String coverUrl;

    private String promptSnapshot;

    private Integer totalScore;

    private Integer characterConsistencyScore;

    private Integer visualQualityScore;

    private Integer motionContinuityScore;

    private Integer audioReadinessScore;

    /**
     * pass / review / reject.
     */
    private String verdict;

    private String issueSummary;

    private String suggestion;

    @Builder.Default
    private Boolean selected = false;

    /**
     * auto_rule / agent / manual.
     */
    private String reviewerType;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String scoreDetails;
}
