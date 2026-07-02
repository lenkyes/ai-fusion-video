INSERT INTO `afv_asset_item` (
    `asset_id`,
    `item_type`,
    `name`,
    `properties`,
    `sort_order`,
    `source_type`,
    `deleted`,
    `create_time`,
    `update_time`
)
SELECT
    a.`id`,
    'three_view',
    CONCAT(COALESCE(a.`name`, '角色'), ' 三视图'),
    COALESCE(
        NULLIF((
            SELECT initial_item.`properties`
            FROM `afv_asset_item` initial_item
            WHERE initial_item.`asset_id` = a.`id`
              AND initial_item.`item_type` = 'initial'
              AND initial_item.`deleted` = 0
              AND initial_item.`properties` IS NOT NULL
              AND initial_item.`properties` <> ''
            ORDER BY initial_item.`sort_order` ASC, initial_item.`id` ASC
            LIMIT 1
        ), ''),
        a.`properties`
    ),
    COALESCE(sort_info.`next_sort_order`, 1),
    COALESCE(a.`source_type`, 1),
    0,
    NOW(),
    NOW()
FROM `afv_asset` a
LEFT JOIN (
    SELECT `asset_id`, MAX(COALESCE(`sort_order`, 0)) + 1 AS `next_sort_order`
    FROM `afv_asset_item`
    WHERE `deleted` = 0
    GROUP BY `asset_id`
) sort_info ON sort_info.`asset_id` = a.`id`
LEFT JOIN (
    SELECT `asset_id`
    FROM `afv_asset_item`
    WHERE `item_type` = 'three_view'
      AND `deleted` = 0
    GROUP BY `asset_id`
) existing_three_view ON existing_three_view.`asset_id` = a.`id`
WHERE a.`type` = 'character'
  AND a.`deleted` = 0
  AND existing_three_view.`asset_id` IS NULL;
