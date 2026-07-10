package com.stonewu.fusion.controller.asset.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 创建子资产请求 VO
 */
@Schema(description = "创建子资产请求")
@Data
public class AssetItemCreateReqVO {

    @NotNull(message = "资产ID不能为空")
    private Long assetId;

    @Schema(description = "形态根项ID；创建角色 three_view 时指定")
    private Long parentItemId;

    private String itemType;
    private String name;
    private String imageUrl;
    private String thumbnailUrl;
    private String properties;
    private Integer sortOrder;
    private String aiPrompt;
}
