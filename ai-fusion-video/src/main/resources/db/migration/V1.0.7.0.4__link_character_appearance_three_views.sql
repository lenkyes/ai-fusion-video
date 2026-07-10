ALTER TABLE `afv_asset_item`
    ADD COLUMN `parent_item_id` bigint NULL DEFAULT NULL COMMENT '角色三视图所属形态根项ID' AFTER `asset_id`,
    ADD INDEX `idx_asset_item_appearance_three_view`
        (`asset_id` ASC, `parent_item_id` ASC, `item_type` ASC, `deleted` ASC) USING BTREE;

-- 将每个角色最早的旧全局三视图归到默认 initial 形态。
UPDATE `afv_asset_item` three_view
JOIN (
    SELECT initial_item.`asset_id`,
           initial_item.`id` AS `appearance_item_id`,
           legacy_three_view.`id` AS `three_view_item_id`
    FROM (
        SELECT ranked_initial.`asset_id`, ranked_initial.`id`
        FROM (
            SELECT item.`asset_id`, item.`id`,
                   ROW_NUMBER() OVER (
                       PARTITION BY item.`asset_id`
                       ORDER BY COALESCE(item.`sort_order`, 0), item.`id`
                   ) AS row_num
            FROM `afv_asset_item` item
            JOIN `afv_asset` asset ON asset.`id` = item.`asset_id`
            WHERE asset.`type` = 'character'
              AND asset.`deleted` = 0
              AND item.`item_type` = 'initial'
              AND item.`deleted` = 0
        ) ranked_initial
        WHERE ranked_initial.row_num = 1
    ) initial_item
    JOIN (
        SELECT ranked_three_view.`asset_id`, ranked_three_view.`id`
        FROM (
            SELECT item.`asset_id`, item.`id`,
                   ROW_NUMBER() OVER (
                       PARTITION BY item.`asset_id`
                       ORDER BY COALESCE(item.`sort_order`, 0), item.`id`
                   ) AS row_num
            FROM `afv_asset_item` item
            JOIN `afv_asset` asset ON asset.`id` = item.`asset_id`
            WHERE asset.`type` = 'character'
              AND asset.`deleted` = 0
              AND item.`item_type` = 'three_view'
              AND item.`parent_item_id` IS NULL
              AND item.`deleted` = 0
        ) ranked_three_view
        WHERE ranked_three_view.row_num = 1
    ) legacy_three_view ON legacy_three_view.`asset_id` = initial_item.`asset_id`
) mapping ON mapping.`three_view_item_id` = three_view.`id`
SET three_view.`parent_item_id` = mapping.`appearance_item_id`,
    three_view.`update_time` = NOW()
WHERE three_view.`parent_item_id` IS NULL
  AND three_view.`deleted` = 0;

-- 为所有已有角色形态根项补建专属三视图。已有绑定项不会重复创建。
INSERT INTO `afv_asset_item` (
    `asset_id`,
    `parent_item_id`,
    `item_type`,
    `name`,
    `properties`,
    `sort_order`,
    `source_type`,
    `deleted`,
    `create_time`,
    `update_time`
)
SELECT missing.`asset_id`,
       missing.`appearance_item_id`,
       'three_view',
       LEFT(CONCAT(COALESCE(NULLIF(missing.`appearance_name`, ''), NULLIF(missing.`asset_name`, ''), '角色'), ' 三视图'), 128),
       COALESCE(NULLIF(missing.`appearance_properties`, ''), missing.`asset_properties`),
       missing.`next_sort_order`,
       COALESCE(missing.`appearance_source_type`, missing.`asset_source_type`, 1),
       0,
       NOW(),
       NOW()
FROM (
    SELECT root.`asset_id`,
           root.`id` AS `appearance_item_id`,
           root.`name` AS `appearance_name`,
           root.`properties` AS `appearance_properties`,
           root.`source_type` AS `appearance_source_type`,
           asset.`name` AS `asset_name`,
           asset.`properties` AS `asset_properties`,
           asset.`source_type` AS `asset_source_type`,
           COALESCE(sort_info.`max_sort_order`, 0)
               + ROW_NUMBER() OVER (
                   PARTITION BY root.`asset_id`
                   ORDER BY COALESCE(root.`sort_order`, 0), root.`id`
               ) AS `next_sort_order`
    FROM `afv_asset_item` root
    JOIN `afv_asset` asset ON asset.`id` = root.`asset_id`
    LEFT JOIN (
        SELECT item.`asset_id`, MAX(COALESCE(item.`sort_order`, 0)) AS `max_sort_order`
        FROM `afv_asset_item` item
        WHERE item.`deleted` = 0
        GROUP BY item.`asset_id`
    ) sort_info ON sort_info.`asset_id` = root.`asset_id`
    LEFT JOIN `afv_asset_item` linked_three_view
        ON linked_three_view.`asset_id` = root.`asset_id`
       AND linked_three_view.`parent_item_id` = root.`id`
       AND linked_three_view.`item_type` = 'three_view'
       AND linked_three_view.`deleted` = 0
    WHERE asset.`type` = 'character'
      AND asset.`deleted` = 0
      AND root.`item_type` IN ('initial', 'variant', 'age', 'costume', 'damaged')
      AND root.`deleted` = 0
      AND linked_three_view.`id` IS NULL
) missing;
