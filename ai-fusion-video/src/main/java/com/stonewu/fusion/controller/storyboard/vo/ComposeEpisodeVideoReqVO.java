package com.stonewu.fusion.controller.storyboard.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "提交分集合成视频请求")
@Data
public class ComposeEpisodeVideoReqVO {

    @Schema(description = "是否生成外挂字幕文件")
    private Boolean generateSubtitleFiles;

    @Schema(description = "是否将字幕烧录到成片")
    private Boolean burnSubtitles;

    @Schema(description = "是否保留镜头视频原音频")
    private Boolean keepOriginalAudio;

    @Schema(description = "原视频音量，1.0 表示原音量")
    private Double originalAudioVolume;

    @Schema(description = "背景音乐 URL，可选")
    private String bgmUrl;

    @Schema(description = "背景音乐音量，1.0 表示原音量")
    private Double bgmVolume;
}
