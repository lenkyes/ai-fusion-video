package com.stonewu.fusion.controller.storyboard.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "提交分集合成视频请求")
@Data
public class ComposeEpisodeVideoReqVO {

    @Schema(description = "在线剪辑片段列表；为空时按原分镜顺序合成")
    private List<EditorClip> clips;

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

    @Schema(description = "成片开头渐入时长，单位秒")
    private Double fadeInDuration;

    @Schema(description = "成片结尾渐出时长，单位秒")
    private Double fadeOutDuration;

    @Data
    public static class EditorClip {
        @Schema(description = "分镜镜头 ID", requiredMode = Schema.RequiredMode.REQUIRED)
        private Long itemId;

        @Schema(description = "编辑器上传素材 URL；仅允许受信媒体存储地址")
        private String sourceUrl;

        @Schema(description = "素材入点，单位秒")
        private Double sourceStart;

        @Schema(description = "片段时长，单位秒")
        private Double duration;

        @Schema(description = "编辑器轨道 ID")
        private String trackId;

        @Schema(description = "轨道类型：video、overlay、audio")
        private String trackType;

        @Schema(description = "轨道是否静音")
        private Boolean trackMuted;

        @Schema(description = "轨道音量，1.0 表示原始音量")
        private Double trackVolume;

        @Schema(description = "片段在所属轨道上的开始时间，单位秒")
        private Double timelineStart;
    }
}
