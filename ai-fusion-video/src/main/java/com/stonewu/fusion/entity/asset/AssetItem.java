package com.stonewu.fusion.entity.asset;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.stonewu.fusion.common.handler.JsonbTypeHandler;
import com.stonewu.fusion.common.BaseEntity;
import lombok.*;

/**
 * 子资产实体（资产的图片/变体）
 * <p>
 * 对应数据库表：afv_asset_item
 * 管理主资产下的子项，如角色的不同角度立绘、服装变体、受伤状态等。
 * 每个子资产可关联一张图片。
 */
@TableName(value = "afv_asset_item", autoResultMap = true)
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssetItem extends BaseEntity {

    /** 主键ID，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属主资产ID */
    private Long assetId;

    /**
     * 所属形态根项ID。仅 three_view 使用，指向同一主资产下的
     * initial / variant / age / costume / damaged 子资产。
     */
    private Long parentItemId;

    /** 子资产类型：initial-初始图 / three_view-角色三视图 / variant-变体 / costume-服装变化 / age-年龄变化 / damaged-损坏状态等 */
    private String itemType;

    /** 子资产名称 */
    private String name;

    /** 图片URL */
    private String imageUrl;

    /** 缩略图URL */
    private String thumbnailUrl;

    /** 动态属性 JSON，存储子资产特有属性 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String properties;

    /** 排列顺序 */
    @Builder.Default
    private Integer sortOrder = 0;

    /** 来源类型：1-用户上传 2-AI生成 */
    @Builder.Default
    private Integer sourceType = 1;

    /** AI生成时使用的提示词 */
    private String aiPrompt;
}
