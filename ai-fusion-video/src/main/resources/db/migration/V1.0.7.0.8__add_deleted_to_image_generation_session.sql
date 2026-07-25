SET @deleted_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='afv_image_generation_session' AND COLUMN_NAME='deleted');
SET @ddl_deleted := IF(@deleted_exists=0,'ALTER TABLE `afv_image_generation_session` ADD COLUMN `deleted` bit(1) NOT NULL DEFAULT b''0''','SELECT 1');
PREPARE stmt_deleted FROM @ddl_deleted;
EXECUTE stmt_deleted;
DEALLOCATE PREPARE stmt_deleted;
