package com.stonewu.fusion.service.ai;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.AgentTool;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.config.ai.AiAgentDefinition;
import com.stonewu.fusion.controller.ai.vo.AiChatReqVO;
import com.stonewu.fusion.controller.ai.vo.AiChatStreamRespVO;
import com.stonewu.fusion.controller.ai.vo.AiReferenceVO;
import com.stonewu.fusion.entity.ai.AiModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import reactor.core.Disposable;

/**
 * AI 助手服务（核心）
 * <p>
 * 基于 Spring AI Alibaba ReactAgent 实现流式对话，支持：
 * - 父/子 Agent（Multi-Agent）架构
 * - Agent 级工具白名单
 * - 并行工具执行
 * - 上下文引用和模板替换
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Deprecated
public class AiAssistantService {

    private final AiModelService aiModelService;
    private final AiAgentService aiAgentService;
    private final AiToolConfigService aiToolConfigService;
    private final AgentConversationService conversationService;
    private final AgentMessageService messageService;
    private final ChatModelFactory chatModelFactory;
    private final ToolExecutorRegistry toolExecutorRegistry;
    private final StringRedisTemplate stringRedisTemplate;
    private final AiStreamRedisService aiStreamRedisService;

    /** 保存后台 AI 流的 Disposable，支持 cancelStream 时真正取消上游调用 */
    private final ConcurrentHashMap<String, Disposable> activeSubscriptions = new ConcurrentHashMap<>();

    private static final String CANCEL_FLAG_KEY = "fv:agent:cancel:";
    private static final Duration CANCEL_FLAG_TTL = Duration.ofHours(1);

    /** 默认系统提示词 */
    private static final String DEFAULT_SYSTEM_PROMPT = """
            你是一个专业的AI视频创作助手，专注于帮助用户进行剧本编辑和分镜设计。

            你的能力包括：
            1. 剧本润色和改写
            2. 分镜脚本生成和编辑
            3. 角色对话优化
            4. 场景描述增强

            当用户提供引用内容时，请基于这些内容进行分析和处理。
            当需要修改内容时，请调用相应的工具来执行操作。

            请用中文回答，保持专业、简洁的风格。
            """;

    /**
     * 流式对话
     */
    public Flux<AiChatStreamRespVO> stream(AiChatReqVO reqVO, Long userId) {
        log.info("[stream] 开始流式调用: message={}, conversationId={}, agentType={}",
                reqVO.getMessage(), reqVO.getConversationId(), reqVO.getAgentType());

        String conversationId = StrUtil.blankToDefault(reqVO.getConversationId(), IdUtil.fastSimpleUUID());
        String messageId = IdUtil.fastSimpleUUID();

        try {
            // 1. 获取 ChatModel
            ChatModel chatModel = getChatModel(reqVO.getModelId());

            // 2. 获取系统提示词和指令
            String systemPrompt = getSystemPrompt(reqVO);
            String instruction = getInstruction(reqVO);

            // 3. 构建工具回调（含 callback 工具和 Agent 子工具），传入 userId 构建执行上下文
            List<ToolCallback> toolCallbacks = buildToolCallbacks(reqVO, chatModel, userId);

            // 4. 构建 ReactAgent
            var agentBuilder = ReactAgent.builder()
                    .name("ai_assistant_agent")
                    .model(chatModel)
                    .systemPrompt(systemPrompt)
                    .instruction(instruction)
                    .returnReasoningContents(true)
                    .saver(new MemorySaver());

            if (!toolCallbacks.isEmpty()) {
                agentBuilder.tools(toolCallbacks.toArray(new ToolCallback[0]));
            }

            // 启用并行工具执行
            if (shouldEnableParallelTools(reqVO, toolCallbacks)) {
                agentBuilder.parallelToolExecution(true);
                agentBuilder.maxParallelTools(10);
                agentBuilder.toolExecutionTimeout(Duration.ofMinutes(10));
                log.info("已启用并行工具执行: maxParallelTools=10, timeout=10min");
            }

            ReactAgent agent = agentBuilder.build();

            // 5. 记录对话索引
            String title = StrUtil.isNotBlank(reqVO.getTitle())
                    ? reqVO.getTitle()
                    : StrUtil.sub(reqVO.getMessage(), 0, 50);
            conversationService.createOrUpdate(
                    conversationId, userId, reqVO.getProjectId(),
                    null, null, reqVO.getAgentType(), title, reqVO.getCategory());

            // 6. 保存用户消息
            messageService.saveUserMessage(conversationId, reqVO.getMessage(), reqVO.getReferencesJson());

            // 7. 流式调用
            String inputMessage = buildInputMessage(reqVO);
            RunnableConfig runnableConfig = RunnableConfig.builder()
                    .threadId(conversationId)
                    .build();

            Flux<NodeOutput> nodeOutputFlux = agent.stream(inputMessage, runnableConfig);

            // 用于累积完整模型回复
            StringBuilder assistantContentBuilder = new StringBuilder();
            StringBuilder reasoningContentBuilder = new StringBuilder();
            AtomicReference<Long> reasoningStartTimeRef = new AtomicReference<>(null);
            AtomicReference<Long> reasoningDurationMsRef = new AtomicReference<>(null);

            // ======== Redis Stream 解耦：后台独立订阅 AI 流 ========
            aiStreamRedisService.cleanup(conversationId);
            aiStreamRedisService.markActive(conversationId);

            Flux<AiChatStreamRespVO> eventFlux = nodeOutputFlux
                    .takeWhile(output -> !isCancelled(conversationId))
                    .flatMap(output -> {
                        List<AiChatStreamRespVO> responses = new ArrayList<>();

                        if (output instanceof StreamingOutput streamingOutput) {
                            OutputType type = streamingOutput.getOutputType();

                            if (type == OutputType.AGENT_MODEL_STREAMING) {
                                Message message = streamingOutput.message();
                                if (message instanceof AssistantMessage assistantMessage) {
                                    String text = assistantMessage.getText();
                                    Object finishReasonObj = assistantMessage.getMetadata().get("finishReason");
                                    Object reasoningContent = assistantMessage.getMetadata().get("reasoningContent");
                                    String finishReason = finishReasonObj != null ? finishReasonObj.toString() : "";
                                    boolean isFinished = "STOP".equals(finishReason)
                                            || "TOOL_CALLS".equals(finishReason);

                                    // 处理思考内容
                                    if (reasoningContent != null && StrUtil.isNotBlank(reasoningContent.toString())) {
                                        reasoningContentBuilder.append(reasoningContent.toString());
                                        reasoningStartTimeRef.compareAndSet(null, System.currentTimeMillis());
                                        responses.add(new AiChatStreamRespVO()
                                                .setMessageId(messageId)
                                                .setConversationId(conversationId)
                                                .setOutputType("REASONING")
                                                .setReasoningContent(reasoningContent.toString())
                                                .setReasoningStartTime(reasoningStartTimeRef.get())
                                                .setFinished(false));
                                    }

                                    if (StrUtil.isNotEmpty(text)) {
                                        assistantContentBuilder.append(text);

                                        Long startTime = reasoningStartTimeRef.get();
                                        Long durationMs = null;
                                        boolean hasReasoningInThisChunk = reasoningContent != null
                                                && StrUtil.isNotBlank(reasoningContent.toString());
                                        if (startTime != null && reasoningDurationMsRef.get() == null
                                                && !hasReasoningInThisChunk) {
                                            durationMs = System.currentTimeMillis() - startTime;
                                            reasoningDurationMsRef.set(durationMs);
                                        }

                                        AiChatStreamRespVO contentResp = new AiChatStreamRespVO()
                                                .setMessageId(messageId)
                                                .setConversationId(conversationId)
                                                .setOutputType("CONTENT")
                                                .setContent(text)
                                                .setFinished(false);
                                        if (durationMs != null) {
                                            contentResp.setReasoningDurationMs(durationMs);
                                        }
                                        responses.add(contentResp);
                                    }

                                    // 工具调用
                                    if (assistantMessage.hasToolCalls()) {
                                        String assistantContent = assistantContentBuilder.toString();
                                        String reasoningText = reasoningContentBuilder.toString();
                                        Long durationSnapshot = reasoningDurationMsRef.get();
                                        messageService.saveAssistantMessage(conversationId, assistantContent,
                                                reasoningText, durationSnapshot);
                                        assistantContentBuilder.setLength(0);
                                        reasoningContentBuilder.setLength(0);
                                        reasoningStartTimeRef.set(null);
                                        reasoningDurationMsRef.set(null);

                                        if (isCancelled(conversationId)) {
                                            responses.add(new AiChatStreamRespVO()
                                                    .setMessageId(messageId)
                                                    .setConversationId(conversationId)
                                                    .setOutputType("CANCELLED")
                                                    .setContent("对话已停止")
                                                    .setFinished(true));
                                            return Flux.fromIterable(responses);
                                        }

                                        for (var tc : assistantMessage.getToolCalls()) {
                                            responses.add(new AiChatStreamRespVO()
                                                    .setMessageId(messageId)
                                                    .setConversationId(conversationId)
                                                    .setOutputType("TOOL_CALL")
                                                    .setToolCalls(List.of(new AiChatStreamRespVO.ToolCallVO()
                                                            .setId(tc.id())
                                                            .setName(tc.name())
                                                            .setArguments(tc.arguments())))
                                                    .setFinished(false));
                                        }
                                    }

                                    // 模型输出完成
                                    if (isFinished && !assistantMessage.hasToolCalls()) {
                                        String assistantContent = assistantContentBuilder.toString();
                                        String reasoningText = reasoningContentBuilder.toString();
                                        Long durationSnapshot2 = reasoningDurationMsRef.get();
                                        messageService.saveAssistantMessage(conversationId, assistantContent,
                                                reasoningText, durationSnapshot2);
                                        assistantContentBuilder.setLength(0);
                                        reasoningContentBuilder.setLength(0);
                                        reasoningStartTimeRef.set(null);
                                        reasoningDurationMsRef.set(null);
                                    }
                                }
                            } else if (type == OutputType.AGENT_TOOL_FINISHED) {
                                Message message = streamingOutput.message();
                                if (message instanceof ToolResponseMessage toolResponseMessage) {
                                    var toolResponses = toolResponseMessage.getResponses();
                                    for (var toolResponse : toolResponses) {
                                        String toolResult = stripMarkdownCodeBlock(toolResponse.responseData());
                                        // 根据工具返回内容检测真实执行状态
                                        String toolStatus = detectToolStatus(toolResult);
                                        messageService.saveToolCall(conversationId, toolResponse.name(),
                                                toolStatus, toolResult, null, null);
                                        responses.add(new AiChatStreamRespVO()
                                                .setMessageId(messageId)
                                                .setConversationId(conversationId)
                                                .setOutputType("TOOL_FINISHED")
                                                .setToolCallId(toolResponse.id())
                                                .setToolName(toolResponse.name())
                                                .setToolResult(toolResult)
                                                .setToolStatus(toolStatus)
                                                .setFinished(false));
                                    }
                                }
                            }
                        } else {
                            String nodeId = output.node();
                            if ("__END__".equals(nodeId)) {
                                String remainingContent = assistantContentBuilder.toString();
                                String remainingReasoning = reasoningContentBuilder.toString();
                                Long durationEnd = reasoningDurationMsRef.get();
                                messageService.saveAssistantMessage(conversationId, remainingContent,
                                        remainingReasoning, durationEnd);
                                assistantContentBuilder.setLength(0);
                                reasoningContentBuilder.setLength(0);
                            }
                        }

                        return Flux.fromIterable(responses);
                    })
                    .concatWith(Flux.just(new AiChatStreamRespVO()
                            .setMessageId(messageId)
                            .setConversationId(conversationId)
                            .setOutputType("DONE")
                            .setFinished(true)));

            // 后台独立订阅：将事件写入 Redis Stream（SSE 断开不影响）
            Disposable subscription = eventFlux
                    .doOnNext(event -> aiStreamRedisService.publish(conversationId, event))
                    .doOnComplete(() -> {
                        log.info("[stream] AI 流完成，标记 Redis Stream COMPLETED: {}", conversationId);
                        aiStreamRedisService.markCompleted(conversationId);
                        aiStreamRedisService.scheduleCleanup(conversationId);
                    })
                    .doOnError(e -> {
                        log.error("[stream] AI 流出错", e);
                        aiStreamRedisService.publish(conversationId, new AiChatStreamRespVO()
                                .setConversationId(conversationId)
                                .setOutputType("ERROR")
                                .setError(e.getMessage())
                                .setFinished(true));
                        aiStreamRedisService.markError(conversationId);
                        aiStreamRedisService.scheduleCleanup(conversationId);
                    })
                    .doFinally(signalType -> {
                        activeSubscriptions.remove(conversationId);

                        boolean wasCancelled = signalType == SignalType.CANCEL
                                || (signalType == SignalType.ON_COMPLETE && isCancelled(conversationId));

                        if (wasCancelled) {
                            String remainingContent = assistantContentBuilder.toString();
                            String remainingReasoning = reasoningContentBuilder.toString();
                            if (StrUtil.isNotBlank(remainingContent)) {
                                Long durationCancel = reasoningDurationMsRef.get();
                                messageService.saveAssistantMessage(conversationId, remainingContent,
                                        remainingReasoning, durationCancel);
                            }
                        }

                        String finalStatus = switch (signalType) {
                            case ON_ERROR -> "failed";
                            default -> wasCancelled ? "cancelled" : "completed";
                        };
                        conversationService.finish(conversationId, finalStatus);
                        clearCancelFlag(conversationId);
                    })
                    .subscribe(); // ★ 独立订阅，SSE 断开不影响后台 AI 调用

            activeSubscriptions.put(conversationId, subscription);

            // SSE 从 Redis Stream 读取（解耦）
            return aiStreamRedisService.subscribe(conversationId);

        } catch (Exception e) {
            log.error("[stream] 初始化失败", e);
            return Flux.just(new AiChatStreamRespVO()
                    .setMessageId(IdUtil.fastSimpleUUID())
                    .setConversationId(conversationId)
                    .setOutputType("ERROR")
                    .setFinished(true)
                    .setError("Agent 调用失败: " + e.getMessage()));
        }
    }

    /**
     * 取消对话
     */
    public void cancelStream(String conversationId) {
        if (StrUtil.isBlank(conversationId)) {
            return;
        }
        String cancelKey = CANCEL_FLAG_KEY + conversationId;
        stringRedisTemplate.opsForValue().set(cancelKey, "1", CANCEL_FLAG_TTL);
        log.info("[cancelStream] 已设置取消标志: {}", conversationId);

        // 取消后台订阅
        Disposable sub = activeSubscriptions.remove(conversationId);
        if (sub != null && !sub.isDisposed()) {
            sub.dispose();
            log.info("已取消后台 AI 流订阅: conversationId={}", conversationId);
        }

        // 显式更新数据库状态为 cancelled（不依赖 doFinally 回调）
        conversationService.finish(conversationId, "cancelled");

        // 发送停止事件到 Redis Stream
        aiStreamRedisService.publish(conversationId, new AiChatStreamRespVO()
                .setConversationId(conversationId)
                .setOutputType("CANCELLED")
                .setContent("对话已停止")
                .setFinished(true));
        aiStreamRedisService.markCompleted(conversationId);
        aiStreamRedisService.scheduleCleanup(conversationId);
    }

    /**
     * 重连已有流式对话（页面刷新后恢复）
     */
    public Flux<AiChatStreamRespVO> reconnectStream(String conversationId) {
        log.info("[reconnectStream] 重连: conversationId={}", conversationId);
        String status = aiStreamRedisService.getStatus(conversationId);
        if ("NONE".equals(status) || "COMPLETED".equals(status) || "ERROR".equals(status)) {
            log.info("会话已结束或不存在，返回空流: conversationId={}, status={}", conversationId, status);
            return Flux.empty();
        }
        // 从头重读 Stream（包含所有历史事件）
        return aiStreamRedisService.subscribe(conversationId);
    }

    /**
     * 获取流状态
     */
    public String getStreamStatus(String conversationId) {
        return aiStreamRedisService.getStatus(conversationId);
    }

    // ========== 私有方法 ==========

    private ChatModel getChatModel(Long modelId) {
        AiModel model;
        if (modelId != null) {
            model = aiModelService.getById(modelId);
            if (model == null) {
                throw new BusinessException("AI 模型不存在: " + modelId);
            }
        } else {
            model = aiModelService.getDefaultByType(1); // CHAT type
            if (model == null) {
                throw new BusinessException("未配置默认对话模型");
            }
        }

        return chatModelFactory.getOrCreate(model);
    }

    private String getSystemPrompt(AiChatReqVO reqVO) {
        // 优先使用请求中的
        if (StrUtil.isNotBlank(reqVO.getSystemPrompt())) {
            return reqVO.getSystemPrompt();
        }
        // 尝试从 agent 配置获取
        if (StrUtil.isNotBlank(reqVO.getAgentType())) {
            AiAgentDefinition agentConfig = aiAgentService.getByType(reqVO.getAgentType());
            if (agentConfig != null && StrUtil.isNotBlank(agentConfig.getSystemPrompt())) {
                return agentConfig.getSystemPrompt();
            }
        }
        return DEFAULT_SYSTEM_PROMPT;
    }

    /**
     * 获取指令（含动态能力摘要和模板替换）
     */
    private String getInstruction(AiChatReqVO reqVO) {
        if (StrUtil.isNotBlank(reqVO.getInstruction())) {
            return reqVO.getInstruction();
        }
        if (StrUtil.isBlank(reqVO.getAgentType())) {
            return "";
        }

        AiAgentDefinition agentConfig = aiAgentService.getByType(reqVO.getAgentType());
        if (agentConfig == null || StrUtil.isBlank(agentConfig.getInstructionTemplate())) {
            return "";
        }

        String instruction = agentConfig.getInstructionTemplate();

        // 如果模板中包含 %s 格式占位符（用于能力摘要），进行替换
        if (instruction.contains("%s") && Integer.valueOf(1).equals(agentConfig.getEnableTools())) {
            String allCapabilities = buildCapabilitySummary(reqVO.getAgentType(), null);
            String enabledCapabilities = buildCapabilitySummary(reqVO.getAgentType(), reqVO.getEnabledTools());
            instruction = String.format(instruction, allCapabilities, enabledCapabilities);
        }

        // 替换命名占位符（如 {episodeId}、{scriptId}）
        instruction = replaceTemplateVariables(instruction, reqVO);

        return instruction;
    }

    /**
     * 构建能力摘要（工具显示名称列表）
     */
    private String buildCapabilitySummary(String agentType, List<String> filterToolNames) {
        List<ToolExecutor> tools = aiToolConfigService.getEnabledToolsByAgent(agentType);
        List<AiAgentDefinition.SubAgentToolDef> subAgentTools = aiToolConfigService.getSubAgentTools(agentType);

        StringBuilder sb = new StringBuilder();
        for (ToolExecutor tool : tools) {
            if (filterToolNames == null || filterToolNames.isEmpty()
                    || filterToolNames.contains(tool.getToolName())) {
                sb.append("- ").append(tool.getDisplayName()).append("\n");
            }
        }
        for (AiAgentDefinition.SubAgentToolDef subTool : subAgentTools) {
            if (filterToolNames == null || filterToolNames.isEmpty()
                    || filterToolNames.contains(subTool.getToolName())) {
                sb.append("- ").append(subTool.getDisplayName()).append("\n");
            }
        }
        return sb.toString().trim();
    }

    /**
     * 替换模板中的命名占位符
     * <p>
     * 变量来源优先级：projectId > autoReferences > context
     * 替换模板中的 {typeId} 占位符（如 {scriptId}、{episodeId}）
     */
    private String replaceTemplateVariables(String template, AiChatReqVO reqVO) {
        if (StrUtil.isBlank(template)) {
            return template;
        }

        // 1. 注入 projectId
        if (reqVO.getProjectId() != null) {
            template = template.replace("{projectId}", String.valueOf(reqVO.getProjectId()));
        }

        if (template.contains("{storyboardDurationRule}")) {
            template = template.replace("{storyboardDurationRule}",
                    VideoTemplateContext.buildStoryboardRule(reqVO.getContext()));
        }

        // 2. 注入 autoReferences 中的上下文 ID
        if (CollUtil.isNotEmpty(reqVO.getAutoReferences())) {
            for (AiReferenceVO ref : reqVO.getAutoReferences()) {
                if (ref.getId() != null && StrUtil.isNotBlank(ref.getType())) {
                    template = template.replace("{" + ref.getType() + "Id}",
                            String.valueOf(ref.getId()));
                }
            }
        }

        // 3. 回退：从 context 中提取变量（安全网，防止 autoReferences 遗漏）
        if (reqVO.getContext() != null && template.contains("{")) {
            for (Map.Entry<String, Object> entry : reqVO.getContext().entrySet()) {
                String placeholder = "{" + entry.getKey() + "}";
                if (entry.getValue() != null && template.contains(placeholder)) {
                    template = template.replace(placeholder, String.valueOf(entry.getValue()));
                    log.info("[replaceTemplateVariables] 从 context 回退替换: {}={}", entry.getKey(), entry.getValue());
                }
            }
        }

        // 4. 检测未替换的变量，输出警告
        if (template.matches(".*\\{[a-zA-Z]+Id}.*")) {
            log.warn("[replaceTemplateVariables] 模板中仍有未替换的变量: {}",
                    template.substring(0, Math.min(template.length(), 200)));
        }

        return template;
    }

    /**
     * 构建工具回调列表
     * <p>
     * 包含两种工具类型：
     * 1. callback 工具：直接调用本地 ToolExecutor
     * 2. agent 工具：创建子 ReactAgent 并封装为 AgentTool
     */
    private List<ToolCallback> buildToolCallbacks(AiChatReqVO reqVO, ChatModel chatModel, Long userId) {
        String agentType = reqVO.getAgentType();

        // 获取 Agent 配置，检查是否启用工具
        if (StrUtil.isNotBlank(agentType)) {
            AiAgentDefinition agentDef = aiAgentService.getByType(agentType);
            if (agentDef != null && !Integer.valueOf(1).equals(agentDef.getEnableTools())) {
                return List.of();
            }
        }

        // 构建工具执行上下文（在 HTTP 请求线程中，userId 来自 Controller 层已认证的用户）
        ToolExecutionContext toolContext = ToolExecutionContext.builder()
                .userId(userId)
                .ownerType(1)
                .ownerId(userId)
                .requestContext(reqVO.getContext() == null || reqVO.getContext().isEmpty()
                        ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(reqVO.getContext())))
                .build();

        List<ToolCallback> callbacks = new ArrayList<>();

        // 1. 构建 callback 工具
        List<ToolExecutor> enabledTools = aiToolConfigService.getEnabledToolsByAgent(agentType);
        for (ToolExecutor tool : enabledTools) {
            // 与前端 enabledTools 取交集
            if (CollUtil.isNotEmpty(reqVO.getEnabledTools())
                    && !reqVO.getEnabledTools().contains(tool.getToolName())) {
                continue;
            }
            callbacks.add(buildCallbackToolCallback(tool, reqVO, toolContext));
        }

        // 2. 构建 Agent 子工具
        List<AiAgentDefinition.SubAgentToolDef> subAgentTools = aiToolConfigService.getSubAgentTools(agentType);
        for (AiAgentDefinition.SubAgentToolDef subAgentTool : subAgentTools) {
            // 与前端 enabledTools 取交集
            if (CollUtil.isNotEmpty(reqVO.getEnabledTools())
                    && !reqVO.getEnabledTools().contains(subAgentTool.getToolName())) {
                continue;
            }
            ToolCallback agentCallback = buildAgentToolCallback(subAgentTool, chatModel, reqVO, toolContext);
            if (agentCallback != null) {
                callbacks.add(agentCallback);
            }
        }

        log.info("工具回调构建完成: callback工具={}, Agent工具={}, 总计={}",
                enabledTools.size(), subAgentTools.size(), callbacks.size());
        return callbacks;
    }

    /**
     * 构建 callback 类型工具回调
     * <p>
     * 直接调用本地 ToolExecutorRegistry 执行工具。
     * 通过 ToolExecutionContext 显式传递用户身份，不依赖 SecurityContext（ThreadLocal），
     * 兼容 Reactor 异步线程和未来微服务化场景。
     */
    private ToolCallback buildCallbackToolCallback(ToolExecutor tool, AiChatReqVO reqVO,
            ToolExecutionContext toolExecContext) {
        ToolDefinition toolDefinition = ToolDefinition.builder()
                .name(tool.getToolName())
                .description(tool.getToolDescription() != null ? tool.getToolDescription() : tool.getDisplayName())
                .inputSchema(tool.getParametersSchema() != null
                        ? tool.getParametersSchema()
                        : "{\"type\":\"object\",\"properties\":{}}")
                .build();

        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return toolDefinition;
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return ToolMetadata.builder().build();
            }

            @Override
            public String call(String toolInput) {
                log.info("[ToolCallback] 工具被调用: name={}, input={}", toolDefinition.name(), toolInput);

                // 取消检查：工具执行前检查取消状态
                String convId = reqVO.getConversationId();
                if (StrUtil.isNotBlank(convId) && isCancelled(convId)) {
                    log.info("对话已被取消，跳过工具执行: tool={}, conversationId={}", toolDefinition.name(), convId);
                    return JSONUtil.toJsonStr(Map.of(
                            "status", "cancelled",
                            "message", "对话已被取消，工具未执行"));
                }

                return toolExecutorRegistry.execute(toolDefinition.name(), toolInput, toolExecContext);
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                return call(toolInput);
            }
        };
    }

    /**
     * 构建 Agent 类型工具回调（子 Agent）
     * <p>
     * 动态创建子 ReactAgent，设置 systemPrompt/instruction/outputSchema，
     * 并通过 AgentTool.getFunctionToolCallback() 封装为 ToolCallback。
     * 子 Agent 的内部工具直接从 ToolExecutorRegistry 构建本地回调。
     *
     * @param subAgentToolDef 子 Agent 工具定义
     * @param chatModel       父 Agent 使用的 ChatModel（子 Agent 复用）
     * @param reqVO           原始请求（用于模板替换）
     * @return AgentTool 封装的 ToolCallback
     */
    private ToolCallback buildAgentToolCallback(AiAgentDefinition.SubAgentToolDef subAgentToolDef,
            ChatModel chatModel, AiChatReqVO reqVO,
            ToolExecutionContext toolExecContext) {
        try {
            String subAgentType = subAgentToolDef.getRefAgentType();

            // 查出子 Agent 配置
            AiAgentDefinition subAgentDef = aiAgentService.getByType(subAgentType);

            // 确定 systemPrompt：工具定义覆盖 > 子 Agent 默认值
            String systemPrompt = StrUtil.isNotBlank(subAgentToolDef.getSystemPromptOverride())
                    ? subAgentToolDef.getSystemPromptOverride()
                    : (subAgentDef != null ? subAgentDef.getSystemPrompt() : null);

            // 确定 instruction：工具定义覆盖 > 子 Agent 默认值
            String instruction = StrUtil.isNotBlank(subAgentToolDef.getInstructionOverride())
                    ? subAgentToolDef.getInstructionOverride()
                    : (subAgentDef != null ? subAgentDef.getInstructionTemplate() : null);

            // 替换模板变量
            if (StrUtil.isNotBlank(instruction)) {
                instruction = replaceTemplateVariables(instruction, reqVO);
            }

            // 构建子 Agent 的内部工具（直接本地调用）
            List<ToolCallback> subToolCallbacks = new ArrayList<>();
            if (subAgentDef != null && Integer.valueOf(1).equals(subAgentDef.getEnableTools())) {
                List<ToolExecutor> subTools = aiToolConfigService.getEnabledToolsByAgent(subAgentType);
                for (ToolExecutor subTool : subTools) {
                    subToolCallbacks.add(buildCallbackToolCallback(subTool, reqVO, toolExecContext));
                }
            }

            // 创建子 ReactAgent
            var subAgentBuilder = ReactAgent.builder()
                    .name(subAgentToolDef.getToolName())
                    .model(chatModel)
                    .description(subAgentToolDef.getDescription());

            if (StrUtil.isNotBlank(systemPrompt)) {
                subAgentBuilder.systemPrompt(systemPrompt);
            }
            if (StrUtil.isNotBlank(instruction)) {
                subAgentBuilder.instruction(instruction);
            }
            if (StrUtil.isNotBlank(subAgentToolDef.getParametersSchema())) {
                subAgentBuilder.inputSchema(subAgentToolDef.getParametersSchema());
            }
            if (StrUtil.isNotBlank(subAgentToolDef.getOutputSchema())) {
                subAgentBuilder.outputSchema(subAgentToolDef.getOutputSchema());
            }
            if (!subToolCallbacks.isEmpty()) {
                subAgentBuilder.tools(subToolCallbacks);
            }

            ReactAgent subAgent = subAgentBuilder.build();
            log.info("子 Agent 构建完成: name={}, tools={}, hasOutputSchema={}",
                    subAgentToolDef.getToolName(), subToolCallbacks.size(),
                    StrUtil.isNotBlank(subAgentToolDef.getOutputSchema()));

            // 通过 AgentTool 封装为 ToolCallback
            return AgentTool.getFunctionToolCallback(subAgent);

        } catch (Exception e) {
            log.error("构建 Agent 工具失败: name={}, error={}", subAgentToolDef.getToolName(), e.getMessage(), e);
            return null;
        }
    }

    /**
     * 判断是否应启用并行工具执行
     */
    private boolean shouldEnableParallelTools(AiChatReqVO reqVO, List<ToolCallback> toolCallbacks) {
        // 请求显式指定
        if (Boolean.TRUE.equals(reqVO.getEnableParallelTools())) {
            return true;
        }
        // 自动检测：如果包含 Agent 类型工具，启用并行执行
        String agentType = reqVO.getAgentType();
        if (StrUtil.isNotBlank(agentType)) {
            List<AiAgentDefinition.SubAgentToolDef> subAgentTools = aiToolConfigService.getSubAgentTools(agentType);
            if (CollUtil.isNotEmpty(subAgentTools)) {
                log.info("检测到 Agent 类型工具，自动启用并行执行: agentType={}", agentType);
                return true;
            }
        }
        return false;
    }

    /**
     * 构建输入消息（含上下文引用和页面上下文）
     */
    private String buildInputMessage(AiChatReqVO reqVO) {
        StringBuilder message = new StringBuilder();

        // 构建合并的上下文
        Map<String, Object> fullContext = buildContext(reqVO);

        if (!fullContext.isEmpty()) {
            message.append("<references>\n");
            for (Map.Entry<String, Object> entry : fullContext.entrySet()) {
                message.append("<reference name=\"").append(entry.getKey()).append("\">\n");
                message.append(entry.getValue());
                message.append("\n</reference>\n\n");
            }
            message.append("</references>\n\n");
            message.append("<user_request>\n");
        }

        message.append(reqVO.getMessage());

        if (!fullContext.isEmpty()) {
            message.append("\n</user_request>");
        }

        return message.toString();
    }

    /**
     * 构建上下文（处理引用内容）
     * <p>
     * 包含：
     * 1. 当前页面上下文（autoReferences → page_context）
     * 2. 用户手动引用（references）
     * 3. 请求中已有的 context
     */
    private Map<String, Object> buildContext(AiChatReqVO reqVO) {
        Map<String, Object> context = new LinkedHashMap<>();

        // 1. 添加当前页面上下文
        String pageContext = buildCurrentPageContextPrompt(reqVO);
        if (StrUtil.isNotBlank(pageContext)) {
            context.put("page_context", pageContext);
        }

        // 2. 添加请求中已有的 context
        if (reqVO.getContext() != null) {
            context.putAll(reqVO.getContext());
        }

        // 3. 处理用户手动引用
        if (CollUtil.isNotEmpty(reqVO.getReferences())) {
            for (AiReferenceVO ref : reqVO.getReferences()) {
                try {
                    String key = ref.getTitle() != null ? ref.getTitle() : ref.getType() + "_" + ref.getId();

                    // 解析 metadata，检查是否有 fullText
                    String fullText = null;
                    Integer startLine = null;
                    Integer endLine = null;
                    if (StrUtil.isNotBlank(ref.getMetadata())) {
                        try {
                            JSONObject metadataJson = JSONUtil.parseObj(ref.getMetadata());
                            fullText = metadataJson.getStr("fullText");
                            startLine = metadataJson.getInt("startLine");
                            endLine = metadataJson.getInt("endLine");
                        } catch (Exception e) {
                            log.warn("解析引用元数据失败: metadata={}", ref.getMetadata(), e);
                        }
                    }

                    StringBuilder contentBuilder = new StringBuilder();
                    contentBuilder.append("<meta>\n");
                    contentBuilder.append("  <type>").append(ref.getType()).append("</type>\n");
                    contentBuilder.append("  <id>").append(ref.getId()).append("</id>\n");

                    if (StrUtil.isNotBlank(fullText)) {
                        contentBuilder.append("  <line_range start=\"").append(startLine)
                                .append("\" end=\"").append(endLine).append("\" />\n");
                        contentBuilder.append("</meta>\n");
                        contentBuilder.append("<content>\n").append(fullText).append("\n</content>");
                    } else {
                        contentBuilder.append("  <hint>请使用查询工具获取完整内容</hint>\n");
                        contentBuilder.append("</meta>");
                    }

                    context.put(key, contentBuilder.toString());
                } catch (Exception e) {
                    log.warn("处理引用失败: type={}, id={}", ref.getType(), ref.getId(), e);
                }
            }
        }

        return context;
    }

    /**
     * 构建当前页面上下文的 Prompt 片段
     * <p>
     * 从 autoReferences 生成结构化 XML，告知 AI 用户当前所在页面的核心 ID 映射
     */
    private String buildCurrentPageContextPrompt(AiChatReqVO reqVO) {
        List<AiReferenceVO> autoRefs = reqVO.getAutoReferences();
        if (CollUtil.isEmpty(autoRefs)) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<page_context>\n");
        if (reqVO.getProjectId() != null && reqVO.getProjectId() > 0) {
            sb.append("  <project_id>").append(reqVO.getProjectId()).append("</project_id>\n");
        }

        for (AiReferenceVO autoRef : autoRefs) {
            String type = autoRef.getType();
            Long id = autoRef.getId();

            if (id != null && id > 0) {
                switch (type) {
                    case "script" -> sb.append("  <script_id>").append(id).append("</script_id>\n");
                    case "storyboard" -> sb.append("  <storyboard_id>").append(id).append("</storyboard_id>\n");
                    case "asset", "character", "scene" -> {
                        sb.append("  <asset_id>").append(id).append("</asset_id>\n");
                        sb.append("  <asset_type>").append(type).append("</asset_type>\n");
                    }
                    case "project" -> {
                        /* projectId 已在上面设置 */ }
                    default -> sb.append("  <").append(type).append("_id>").append(id)
                            .append("</").append(type).append("_id>\n");
                }
            }
        }

        sb.append("</page_context>");
        return sb.toString();
    }

    /**
     * 剥离 LLM 输出中常见的 markdown 代码块包裹
     * <p>
     * LLM（特别是作为子 Agent 工具调用时）常在 JSON 输出外面加上 ```json ... ``` 包裹
     */
    private String stripMarkdownCodeBlock(String text) {
        if (StrUtil.isBlank(text)) {
            return text;
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline < 0) {
                return text;
            }
            String body = trimmed.substring(firstNewline + 1);
            if (body.endsWith("```")) {
                body = body.substring(0, body.length() - 3).trim();
            }
            return body;
        }
        return text;
    }

    /**
     * 根据工具返回内容检测真实执行状态
     * <p>
     * 工具执行器在失败时会返回包含 "status":"error" 的 JSON；
     * 子 Agent 调用失败时也可能返回包含错误关键词的文本。
     * 框架层面的 AGENT_TOOL_FINISHED 事件仅表示调用过程完成，不代表业务执行成功。
     */
    private String detectToolStatus(String toolResult) {
        if (StrUtil.isBlank(toolResult)) {
            return "success";
        }
        // 检查 JSON 格式的错误标志（ToolExecutorRegistry 返回格式）
        try {
            if (toolResult.trim().startsWith("{")) {
                JSONObject json = JSONUtil.parseObj(toolResult);
                String status = json.getStr("status");
                if ("error".equals(status) || "not_implemented".equals(status)) {
                    return "error";
                }
            }
        } catch (Exception ignored) {
            // 非 JSON 格式，继续检查文本内容
        }
        // 检查常见的错误文本模式
        String lower = toolResult.toLowerCase();
        if (lower.contains("not all variables were replaced")
                || lower.contains("missing variable names")
                || lower.contains("工具执行失败")
                || lower.contains("执行异常")) {
            return "error";
        }
        return "success";
    }

    private boolean isCancelled(String conversationId) {
        if (StrUtil.isBlank(conversationId)) {
            return false;
        }
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(CANCEL_FLAG_KEY + conversationId));
    }

    private void clearCancelFlag(String conversationId) {
        if (StrUtil.isNotBlank(conversationId)) {
            stringRedisTemplate.delete(CANCEL_FLAG_KEY + conversationId);
        }
    }
}
