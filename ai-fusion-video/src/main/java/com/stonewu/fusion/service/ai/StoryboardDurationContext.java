package com.stonewu.fusion.service.ai;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;

/**
 * 解析 AI 请求上下文中的分镜镜头时长策略。
 */
public final class StoryboardDurationContext {

    public static final String MODE_KEY = "storyboardMode";
    public static final String SHOT_DURATION_KEY = "shotDuration";
    public static final String MODE_REGULAR = "regular";
    public static final String MODE_CUSTOM = "custom";

    private static final BigDecimal MIN_DURATION = BigDecimal.ONE;
    private static final BigDecimal MAX_DURATION = BigDecimal.valueOf(60);

    private StoryboardDurationContext() {
    }

    /**
     * 自定义模式返回固定时长，常规模式返回 {@code null}。
     */
    public static BigDecimal resolveFixedDuration(Map<String, Object> requestContext) {
        String mode = resolveMode(requestContext);
        if (MODE_REGULAR.equals(mode)) {
            return null;
        }
        if (!MODE_CUSTOM.equals(mode)) {
            throw new IllegalArgumentException("storyboardMode 仅支持 regular 或 custom");
        }

        Object rawDuration = requestContext != null ? requestContext.get(SHOT_DURATION_KEY) : null;
        if (rawDuration == null || rawDuration.toString().isBlank()) {
            throw new IllegalArgumentException("自定义分镜模式必须提供 shotDuration");
        }

        BigDecimal duration;
        try {
            duration = new BigDecimal(rawDuration.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("shotDuration 必须是 1 到 60 的整数秒", e);
        }

        if (duration.stripTrailingZeros().scale() > 0
                || duration.compareTo(MIN_DURATION) < 0
                || duration.compareTo(MAX_DURATION) > 0) {
            throw new IllegalArgumentException("shotDuration 必须是 1 到 60 的整数秒");
        }
        return duration.setScale(0);
    }

    public static String buildPromptRule(Map<String, Object> requestContext) {
        BigDecimal fixedDuration = resolveFixedDuration(requestContext);
        if (fixedDuration == null) {
            return "常规模式（regular）：根据剧情节奏分别设计每个镜头的时长，各镜头不要求统一时长。";
        }
        return "自定义长镜头模式（custom）：每个镜头必须严格固定为 "
                + fixedDuration.toPlainString()
                + " 秒。镜头的动作、对白、构图和运镜必须足以覆盖完整时长，"
                + "调用 save_storyboard_scene_shots 时每个镜头都必须传入相同 duration。";
    }

    private static String resolveMode(Map<String, Object> requestContext) {
        Object rawMode = requestContext != null ? requestContext.get(MODE_KEY) : null;
        if (rawMode == null || rawMode.toString().isBlank()) {
            return MODE_REGULAR;
        }
        return rawMode.toString().trim().toLowerCase(Locale.ROOT);
    }
}
