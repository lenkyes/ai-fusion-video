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
    public static final BigDecimal REGULAR_MIN_DURATION = BigDecimal.valueOf(4);
    public static final BigDecimal REGULAR_MAX_DURATION = BigDecimal.valueOf(15);
    public static final BigDecimal REGULAR_DEFAULT_DURATION = BigDecimal.valueOf(5);

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
        if (MODE_REGULAR.equals(resolveMode(requestContext))) {
            return "常规模式：每个镜头 duration 必须在 4-15 秒之间，缺失时按 5 秒规划；禁止生成低于 4 秒或超过 15 秒的镜头。应根据剧情节奏在范围内设计镜头，而不是依赖模型默认时长。";
        }
        BigDecimal fixedDuration = resolveFixedDuration(requestContext);
        if (fixedDuration == null) {
            return "常规模式（regular）：根据剧情节奏分别设计每个镜头的时长，各镜头不要求统一时长。";
        }
        return "自定义长镜头模式（custom）：每个镜头必须严格固定为 "
                + fixedDuration.toPlainString()
                + " 秒。镜头数量必须按目标时长重新规划，不能沿用常规模式后只拉长 duration；"
                + "以常规 4-6 秒镜头为基准，15 秒镜头通常应合并约 2-3 个连续常规镜头的动作、对白和表演节拍，"
                + "优先减少镜头数量并让一个镜头完成连续叙事。每个 15 秒镜头必须设计 3-5 个前后衔接、可被拍摄的画面节拍，"
                + "至少包含开头建立、中段发展或转折、结尾落点，并写清人物动作与反应、环境或道具互动、构图变化和连续运镜路径；"
                + "只有一句独白、一个静态动作或单一表情且画面没有推进的镜头不合格。对白不能代替视觉叙事，"
                + "即使对白较短，角色表演和画面事件也必须持续覆盖完整时长，"
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
