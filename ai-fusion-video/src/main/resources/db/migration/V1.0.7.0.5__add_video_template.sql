CREATE TABLE IF NOT EXISTS `afv_video_template` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `code` varchar(64) NOT NULL,
  `name` varchar(128) NOT NULL,
  `category` varchar(64) NOT NULL DEFAULT 'general',
  `description` varchar(512) NULL,
  `cover_url` varchar(512) NULL,
  `config_json` longtext NOT NULL,
  `version` int NOT NULL DEFAULT 1,
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '0 draft, 1 published, 2 disabled',
  `sort_order` int NOT NULL DEFAULT 0,
  `created_by` bigint NULL,
  `deleted` tinyint NOT NULL DEFAULT 0,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_video_template_code` (`code`),
  KEY `idx_video_template_status_sort` (`status`, `sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Configurable video creation templates';

INSERT INTO `afv_video_template` (`code`,`name`,`category`,`description`,`config_json`,`version`,`status`,`sort_order`)
VALUES ('pov-lyrical-memory','第一人称 · 抒情回忆','情绪叙事','手持手机主观镜头、克制念白，情绪随尾奏逐步递进。',
'{"duration":60,"aspectRatio":"9:16","projectType":"短剧","storyPrompt":"围绕随机主题创作一个真实、克制的第一人称生活故事。用具体动作和物件承载情绪，不解释道理，不写鸡汤。每次更换人物、地点、关系、关键物件与反转。","negativePrompt":["第三人称全景","航拍","棚拍","广告片构图","拍摄者完整正脸","说教式总结"],"camera":{"perspective":"first_person_pov","device":"handheld_phone","style":["自然轻微晃动","偶发自动对焦","真实环境光","行走与呼吸感","普通生活细节"]},"voiceover":{"enabled":true,"person":"first","tone":"克制、口语化、像深夜回忆","maxSentenceLength":18,"bgmDuckDb":-6},"audio":{"bgmVolume":0.34,"originalAudioVolume":0.18},"beats":[{"id":"open","start":0,"end":8,"label":"记忆入口","purpose":"用动作或物件建立关系","shotGuidance":"慢速手持主观镜头","voiceoverGuidance":"一到两句，不完整交代背景"},{"id":"setup","start":8,"end":22,"label":"生活片段","purpose":"给出具体相处细节","shotGuidance":"日常 POV 镜头并保留环境声","voiceoverGuidance":"描述当时不理解的细节"},{"id":"rise","start":22,"end":38,"label":"遗憾递进","purpose":"揭示没有说出口的情绪","shotGuidance":"行走、回头或离开","voiceoverGuidance":"靠近核心遗憾但不总结"},{"id":"climax","start":38,"end":50,"label":"情绪高潮","purpose":"完成转折","shotGuidance":"关键物件特写与主观回望","voiceoverGuidance":"最重要的一到两句后留白"},{"id":"outro","start":50,"end":60,"label":"尾奏余韵","purpose":"由音乐承担情绪","shotGuidance":"窗外或逐渐远离的空镜","voiceoverGuidance":"最多一句开放式收尾"}]}',1,1,10)
ON DUPLICATE KEY UPDATE `code`=`code`;
