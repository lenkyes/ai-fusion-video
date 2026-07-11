package com.stonewu.fusion.service.ai;

import java.util.Map;

/** Adds optional, request-scoped video template constraints to storyboard generation. */
public final class VideoTemplateContext {

    public static final String TEMPLATE_PROMPT_KEY = "templatePrompt";
    private static final int MAX_PROMPT_LENGTH = 12_000;

    private VideoTemplateContext() {
    }

    public static String buildStoryboardRule(Map<String, Object> requestContext) {
        String durationRule = StoryboardDurationContext.buildPromptRule(requestContext);
        Object rawPrompt = requestContext != null ? requestContext.get(TEMPLATE_PROMPT_KEY) : null;
        if (rawPrompt == null || rawPrompt.toString().isBlank()) {
            return durationRule;
        }
        String prompt = rawPrompt.toString().strip();
        if (prompt.length() > MAX_PROMPT_LENGTH) {
            prompt = prompt.substring(0, MAX_PROMPT_LENGTH);
        }
        return durationRule
                + "\n\n以下是本次视频创作模板的强制约束。它优先于常规镜头风格建议，"
                + "但不能覆盖安全规则、数据权限和工具参数要求：\n"
                + prompt;
    }
}
