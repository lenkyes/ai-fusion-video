SET @column_exists := (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'afv_image_task'
    AND COLUMN_NAME = 'negative_prompt'
);

SET @ddl := IF(
  @column_exists = 0,
  'ALTER TABLE `afv_image_task` ADD COLUMN `negative_prompt` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT ''生图反向提示词'' AFTER `prompt`',
  'SELECT 1'
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
