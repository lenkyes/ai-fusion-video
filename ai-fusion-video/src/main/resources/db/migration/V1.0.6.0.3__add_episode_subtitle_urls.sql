ALTER TABLE afv_storyboard_episode
    ADD COLUMN subtitle_srt_url VARCHAR(512) NULL COMMENT '本集外挂字幕SRT URL',
    ADD COLUMN subtitle_ass_url VARCHAR(512) NULL COMMENT '本集外挂字幕ASS URL';
