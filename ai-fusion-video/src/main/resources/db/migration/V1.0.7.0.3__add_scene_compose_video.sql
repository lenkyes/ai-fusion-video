SET @column_exists := (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'afv_storyboard_scene'
    AND COLUMN_NAME = 'composed_video_url'
);
SET @ddl := IF(
  @column_exists = 0,
  'ALTER TABLE `afv_storyboard_scene` ADD COLUMN `composed_video_url` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT ''场次合成视频URL'' AFTER `status`',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists := (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'afv_storyboard_scene'
    AND COLUMN_NAME = 'subtitle_srt_url'
);
SET @ddl := IF(
  @column_exists = 0,
  'ALTER TABLE `afv_storyboard_scene` ADD COLUMN `subtitle_srt_url` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT ''场次外挂字幕SRT URL'' AFTER `composed_video_url`',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists := (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'afv_storyboard_scene'
    AND COLUMN_NAME = 'subtitle_ass_url'
);
SET @ddl := IF(
  @column_exists = 0,
  'ALTER TABLE `afv_storyboard_scene` ADD COLUMN `subtitle_ass_url` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT ''场次外挂字幕ASS URL'' AFTER `subtitle_srt_url`',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists := (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'afv_storyboard_scene'
    AND COLUMN_NAME = 'compose_status'
);
SET @ddl := IF(
  @column_exists = 0,
  'ALTER TABLE `afv_storyboard_scene` ADD COLUMN `compose_status` tinyint NOT NULL DEFAULT 0 COMMENT ''合成状态: 0未开始 1合成中 2已完成 3失败'' AFTER `subtitle_ass_url`',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists := (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'afv_storyboard_scene'
    AND COLUMN_NAME = 'compose_error_msg'
);
SET @ddl := IF(
  @column_exists = 0,
  'ALTER TABLE `afv_storyboard_scene` ADD COLUMN `compose_error_msg` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT ''合成失败原因'' AFTER `compose_status`',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists := (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'afv_storyboard_scene'
    AND COLUMN_NAME = 'composed_at'
);
SET @ddl := IF(
  @column_exists = 0,
  'ALTER TABLE `afv_storyboard_scene` ADD COLUMN `composed_at` datetime NULL COMMENT ''合成完成时间'' AFTER `compose_error_msg`',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
