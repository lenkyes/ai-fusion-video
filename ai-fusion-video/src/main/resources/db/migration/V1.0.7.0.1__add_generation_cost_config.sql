CREATE TABLE IF NOT EXISTS `afv_generation_cost_config` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `model_id` bigint NOT NULL COMMENT 'AI model ID',
  `media_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'image/video',
  `billing_mode` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'per_image' COMMENT 'per_image/per_video/per_second/free',
  `unit_price` decimal(18,6) NOT NULL DEFAULT 0.000000 COMMENT 'Unit price',
  `currency` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'CNY' COMMENT 'Currency',
  `enabled` tinyint NOT NULL DEFAULT 1 COMMENT 'Whether this cost rule is enabled',
  `remark` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT 'Remark',
  `deleted` tinyint NOT NULL DEFAULT 0 COMMENT 'Logical delete flag',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Create time',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_generation_cost_model_media` (`model_id`, `media_type`) USING BTREE,
  INDEX `idx_generation_cost_media_type` (`media_type` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'AI generation cost pricing configs' ROW_FORMAT = Dynamic;
