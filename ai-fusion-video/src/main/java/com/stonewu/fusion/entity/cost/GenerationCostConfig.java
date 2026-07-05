package com.stonewu.fusion.entity.cost;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.stonewu.fusion.common.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.math.BigDecimal;

/**
 * Pricing rules for successful AI generation outputs.
 */
@TableName("afv_generation_cost_config")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GenerationCostConfig extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long modelId;

    /**
     * image / video.
     */
    private String mediaType;

    /**
     * per_image / per_video / per_second / free.
     */
    private String billingMode;

    @Builder.Default
    private BigDecimal unitPrice = BigDecimal.ZERO;

    @Builder.Default
    private String currency = "CNY";

    @Builder.Default
    private Boolean enabled = true;

    private String remark;
}
