CREATE TABLE IF NOT EXISTS `afv_image_generation_session` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `title` varchar(255) NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`), KEY `idx_image_session_user` (`user_id`,`update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @column_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='afv_image_task' AND COLUMN_NAME='session_id');
SET @ddl := IF(@column_exists=0,'ALTER TABLE `afv_image_task` ADD COLUMN `session_id` bigint NULL AFTER `user_id`, ADD KEY `idx_image_task_session` (`session_id`)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
