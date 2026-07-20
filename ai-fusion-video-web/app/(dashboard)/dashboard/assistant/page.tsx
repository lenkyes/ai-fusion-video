"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  Ban,
  Bot,
  Check,
  CheckCircle2,
  Copy,
  Loader2,
  MessageSquare,
  Plus,
  RefreshCw,
  Send,
  Sparkles,
  Trash2,
  XCircle,
} from "lucide-react";
import { toast } from "sonner";
import { AgentPipelineTimeline } from "@/components/dashboard/agent-pipeline/timeline";
import {
  createInitialPipelineState,
  createPendingPipelineState,
  reducePipelineEvent,
} from "@/components/dashboard/agent-pipeline/state";
import { messagesToTimeline } from "@/components/dashboard/notification-panel/history";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import {
  deleteConversation,
  listConversations,
  listMessages,
  type AgentConversation,
  type AgentMessage,
} from "@/lib/api/ai-assistant";
import {
  cancelPipeline,
  pipelineStream,
  type AiChatReq,
} from "@/lib/api/ai-pipeline";
import { projectApi, type Project } from "@/lib/api/project";
import { cn } from "@/lib/utils";

type AssistantStatus = "idle" | "running" | "done" | "error" | "cancelled";

interface HistoryTurn {
  user?: AgentMessage;
  assistantMessages: AgentMessage[];
}

async function copyToClipboard(text: string) {
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text);
      return;
    } catch {
      // Fall through for browsers that expose the API but deny access.
    }
  }

  const textarea = document.createElement("textarea");
  textarea.value = text;
  textarea.style.position = "fixed";
  textarea.style.opacity = "0";
  document.body.appendChild(textarea);
  textarea.focus();
  textarea.select();
  const copied = document.execCommand("copy");
  textarea.remove();

  if (!copied) {
    throw new Error("Clipboard copy failed");
  }
}

function CopyButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false);
  const resetTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    return () => {
      if (resetTimerRef.current) {
        clearTimeout(resetTimerRef.current);
      }
    };
  }, []);

  const handleCopy = async () => {
    try {
      await copyToClipboard(text);
      setCopied(true);
      toast.success("问答已复制");
      if (resetTimerRef.current) {
        clearTimeout(resetTimerRef.current);
      }
      resetTimerRef.current = setTimeout(() => setCopied(false), 1600);
    } catch {
      toast.error("复制失败，请重试");
    }
  };

  const label = copied ? "问答已复制" : "复制问答";

  return (
    <Button
      type="button"
      variant="ghost"
      size="icon-xs"
      onClick={handleCopy}
      disabled={!text.trim()}
      aria-label={label}
      title={label}
      className="shrink-0 text-muted-foreground hover:text-foreground"
    >
      {copied ? <Check className="h-3.5 w-3.5" /> : <Copy className="h-3.5 w-3.5" />}
    </Button>
  );
}

const suggestedPrompts = [
  "帮我构思一个有反转的短剧故事",
  "把这个想法整理成清晰的创作大纲",
  "帮我润色下面这段对白，让人物更有张力",
  "给我一些适合短视频的创意方向",
];

function formatTime(value?: string) {
  if (!value) return "";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  return date.toLocaleString("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function buildHistoryTurns(messages: AgentMessage[]): HistoryTurn[] {
  const turns: HistoryTurn[] = [];
  let current: HistoryTurn | null = null;

  for (const message of messages) {
    if (message.role === "user") {
      current = { user: message, assistantMessages: [] };
      turns.push(current);
      continue;
    }

    if (!current) {
      current = { assistantMessages: [] };
      turns.push(current);
    }
    current.assistantMessages.push(message);
  }

  return turns;
}

function conversationStatusMeta(status?: string) {
  if (!status || status === "idle") {
    return {
      label: "待开始",
      icon: <MessageSquare className="h-3.5 w-3.5 text-muted-foreground" />,
    };
  }
  if (status === "completed" || status === "done") {
    return {
      label: "已完成",
      icon: <CheckCircle2 className="h-3.5 w-3.5 text-emerald-500" />,
    };
  }
  if (status === "running") {
    return {
      label: "运行中",
      icon: <Loader2 className="h-3.5 w-3.5 animate-spin text-blue-500" />,
    };
  }
  if (status === "cancelled") {
    return {
      label: "已取消",
      icon: <Ban className="h-3.5 w-3.5 text-muted-foreground" />,
    };
  }
  if (status === "failed" || status === "error") {
    return {
      label: "出错",
      icon: <XCircle className="h-3.5 w-3.5 text-destructive" />,
    };
  }
  return {
    label: status,
    icon: <MessageSquare className="h-3.5 w-3.5 text-muted-foreground" />,
  };
}

function EmptyAssistantState({
  onPickPrompt,
}: {
  onPickPrompt: (prompt: string) => void;
}) {
  return (
    <div className="flex h-full min-h-[360px] flex-col items-center justify-center px-6 text-center">
      <div className="mb-4 flex h-12 w-12 items-center justify-center rounded-xl border border-primary/20 bg-primary/10">
        <Sparkles className="h-5 w-5 text-primary" />
      </div>
      <h2 className="text-lg font-semibold">AI 创作助手</h2>
      <p className="mt-2 max-w-lg text-sm leading-6 text-muted-foreground">
        全局工作区是通用对话助手；选择具体项目后，才会读取项目上下文并执行项目操作。
      </p>
      <div className="mt-6 grid w-full max-w-2xl gap-2 sm:grid-cols-2">
        {suggestedPrompts.map((prompt) => (
          <button
            key={prompt}
            type="button"
            onClick={() => onPickPrompt(prompt)}
            className="rounded-lg border border-border/35 bg-card/45 px-3 py-2.5 text-left text-xs leading-5 transition-colors hover:border-primary/30 hover:bg-primary/5"
          >
            {prompt}
          </button>
        ))}
      </div>
    </div>
  );
}

export default function DashboardAssistantPage() {
  const [conversations, setConversations] = useState<AgentConversation[]>([]);
  const [historyMessages, setHistoryMessages] = useState<AgentMessage[]>([]);
  const [projects, setProjects] = useState<Project[]>([]);
  const [selectedProjectId, setSelectedProjectId] = useState<string>("none");
  const [activeConversationId, setActiveConversationId] = useState<string | null>(null);
  const [activeConversationDbId, setActiveConversationDbId] = useState<number | null>(null);
  const [input, setInput] = useState("");
  const [status, setStatus] = useState<AssistantStatus>("idle");
  const [error, setError] = useState<string | null>(null);
  const [streamState, setStreamState] = useState(createInitialPipelineState);
  const [pendingUserMessage, setPendingUserMessage] = useState<string | null>(null);
  const [loadingConversations, setLoadingConversations] = useState(true);
  const [loadingMessages, setLoadingMessages] = useState(false);
  const abortRef = useRef<AbortController | null>(null);
  const currentStreamConversationIdRef = useRef<string | null>(null);
  const chatScrollRef = useRef<HTMLDivElement>(null);

  const historyTurns = useMemo(
    () => buildHistoryTurns(historyMessages),
    [historyMessages]
  );
  const isStreaming = status === "running";
  const activeConversation = conversations.find(
    (item) => item.conversationId === activeConversationId
  );
  const selectedProject = projects.find(
    (item) => String(item.id) === selectedProjectId
  );

  const loadConversations = useCallback(async () => {
    setLoadingConversations(true);
    try {
      const resp = await listConversations({
        pageNo: 1,
        pageSize: 50,
        category: "assistant",
      });
      setConversations(resp.list ?? []);
    } finally {
      setLoadingConversations(false);
    }
  }, []);

  const loadConversationMessages = useCallback(async (conversation: AgentConversation) => {
    setActiveConversationId(conversation.conversationId);
    setActiveConversationDbId(conversation.id);
    setLoadingMessages(true);
    setError(null);
    setPendingUserMessage(null);
    setStreamState(createInitialPipelineState());
    setStatus("idle");
    abortRef.current?.abort();
    abortRef.current = null;
    currentStreamConversationIdRef.current = null;
    try {
      const messages = await listMessages(conversation.conversationId);
      setHistoryMessages(messages);
    } catch (err) {
      setHistoryMessages([]);
      setError(err instanceof Error ? err.message : "加载会话失败");
    } finally {
      setLoadingMessages(false);
    }
  }, []);

  useEffect(() => {
    loadConversations().catch((err) => {
      setError(err instanceof Error ? err.message : "加载历史会话失败");
      setLoadingConversations(false);
    });
    projectApi.list()
      .then(setProjects)
      .catch(() => setProjects([]));
  }, [loadConversations]);

  useEffect(() => {
    if (chatScrollRef.current) {
      chatScrollRef.current.scrollTop = chatScrollRef.current.scrollHeight;
    }
  }, [historyTurns, pendingUserMessage, streamState.timeline, status]);

  useEffect(() => {
    if (!activeConversationId) {
      return;
    }
    const conversation = conversations.find(
      (item) => item.conversationId === activeConversationId
    );
    if (conversation) {
      setActiveConversationDbId(conversation.id);
    }
  }, [activeConversationId, conversations]);

  useEffect(() => {
    return () => {
      abortRef.current?.abort();
    };
  }, []);

  const startNewConversation = () => {
    abortRef.current?.abort();
    abortRef.current = null;
    setActiveConversationId(null);
    setActiveConversationDbId(null);
    setHistoryMessages([]);
    setPendingUserMessage(null);
    setStreamState(createInitialPipelineState());
    setStatus("idle");
    setError(null);
  };

  const refreshAssistantData = useCallback(async () => {
    await loadConversations();
    if (!activeConversationId) {
      return;
    }

    setLoadingMessages(true);
    setError(null);
    try {
      const messages = await listMessages(activeConversationId);
      setHistoryMessages(messages);
    } catch (err) {
      setError(err instanceof Error ? err.message : "刷新会话失败");
    } finally {
      setLoadingMessages(false);
    }
  }, [activeConversationId, loadConversations]);

  const handleSend = () => {
    const message = input.trim();
    if (!message || isStreaming) {
      return;
    }

    setInput("");
    setError(null);
    setPendingUserMessage(message);
    setStreamState(createPendingPipelineState());
    setStatus("running");
    currentStreamConversationIdRef.current = activeConversationId;

    const projectId =
      selectedProjectId !== "none" ? Number(selectedProjectId) : undefined;
    const request: AiChatReq = {
      message,
      conversationId: activeConversationId ?? undefined,
      agentType: "ai_media",
      category: "assistant",
      title: activeConversationId ? undefined : message.slice(0, 40),
      projectId,
      enableParallelTools: true,
      context: {
        entry: "dashboard_assistant",
        projectScope: projectId ? "selected_project" : "global_workspace",
      },
    };

    abortRef.current = pipelineStream(request, {
      onEvent: (event) => {
        if (event.conversationId) {
          currentStreamConversationIdRef.current = event.conversationId;
          setActiveConversationId(event.conversationId);
        }
        setStreamState((prev) => reducePipelineEvent(prev, event));
        if (event.outputType === "DONE") {
          setStatus("done");
        }
        if (event.outputType === "ERROR") {
          setStatus("error");
          setError(event.error || "AI 助手执行失败");
        }
        if (event.outputType === "CANCELLED") {
          setStatus("cancelled");
        }
      },
      onError: (err) => {
        abortRef.current = null;
        setStatus("error");
        setError(err.message);
      },
      onComplete: () => {
        abortRef.current = null;
        setStatus((prev) => (prev === "running" ? "done" : prev));
        setTimeout(() => {
          loadConversations().catch(() => {});
          const conversationId = currentStreamConversationIdRef.current;
          if (conversationId) {
            listMessages(conversationId)
              .then((messages) => {
                setHistoryMessages(messages);
                setPendingUserMessage(null);
                setStreamState(createInitialPipelineState());
              })
              .catch(() => {});
          }
        }, 500);
      },
    });
  };

  const handleCancel = async () => {
    abortRef.current?.abort();
    abortRef.current = null;
    const conversationId = currentStreamConversationIdRef.current || activeConversationId;
    if (conversationId) {
      try {
        await cancelPipeline(conversationId);
      } catch {
        // 忽略取消失败，前端仍然停止当前流。
      }
    }
    setStatus("cancelled");
  };

  const handleDeleteConversation = async () => {
    if (!activeConversationDbId) {
      return;
    }
    const ok = window.confirm("确定删除这个 AI 助手会话吗？");
    if (!ok) {
      return;
    }
    await deleteConversation(activeConversationDbId);
    startNewConversation();
    await loadConversations();
  };

  const pickPrompt = (prompt: string) => {
    setInput(prompt);
  };

  const renderHistoryTurn = (turn: HistoryTurn, index: number) => {
    const timeline = messagesToTimeline(turn.assistantMessages);
    const answerText = timeline
      .filter((item) => item.type === "content")
      .map((item) => item.text.trim())
      .filter(Boolean)
      .join("\n\n");
    const questionText = turn.user?.content?.trim();
    return (
      <div key={`turn-${index}`} className="space-y-3">
        {turn.user?.content && (
          <div className="ml-auto flex max-w-[82%] items-start gap-2 rounded-lg bg-primary px-4 py-3 text-sm leading-6 text-primary-foreground">
            <div className="min-w-0 flex-1 whitespace-pre-wrap">{turn.user.content}</div>
            <CopyButton text={questionText ?? ""} />
          </div>
        )}
        {timeline.length > 0 && (
          <div className="max-w-[92%] rounded-lg border border-border/30 bg-card/45 p-3">
            <AgentPipelineTimeline timeline={timeline} isActive={false} />
            {answerText && (
              <div className="mt-2 flex justify-end border-t border-border/20 pt-2">
                <CopyButton text={answerText} />
              </div>
            )}
          </div>
        )}
      </div>
    );
  };

  const statusMeta = conversationStatusMeta(
    status !== "idle" ? status : activeConversation?.status
  );

  return (
    <div className="flex h-[calc(100vh-7rem)] min-h-[620px] max-w-[1400px] gap-4 pb-4">
      <aside className="hidden w-72 shrink-0 rounded-lg border border-border/35 bg-card/45 lg:flex lg:flex-col">
        <div className="flex items-center justify-between border-b border-border/25 px-4 py-3">
          <div>
            <p className="text-sm font-semibold">AI 助手</p>
            <p className="text-xs text-muted-foreground">历史会话</p>
          </div>
          <Button size="icon-sm" variant="outline" onClick={startNewConversation} title="新会话">
            <Plus className="h-4 w-4" />
          </Button>
        </div>

        <div className="border-b border-border/25 p-3">
          <label className="mb-1.5 block text-xs text-muted-foreground">项目上下文</label>
          <select
            value={selectedProjectId}
            onChange={(event) => setSelectedProjectId(event.target.value)}
            disabled={isStreaming}
            className="h-9 w-full rounded-lg border border-border/40 bg-background px-2 text-xs outline-none"
          >
            <option value="none">全局工作区</option>
            {projects.map((project) => (
              <option key={project.id} value={project.id}>
                {project.name}
              </option>
            ))}
          </select>
        </div>

        <div className="flex-1 overflow-y-auto p-2">
          {loadingConversations ? (
            <div className="flex items-center justify-center py-8">
              <Loader2 className="h-4 w-4 animate-spin text-muted-foreground" />
            </div>
          ) : conversations.length === 0 ? (
            <div className="px-3 py-8 text-center text-xs text-muted-foreground">
              暂无助手会话
            </div>
          ) : (
            conversations.map((conversation) => {
              const meta = conversationStatusMeta(conversation.status);
              const selected = conversation.conversationId === activeConversationId;
              return (
                <button
                  key={conversation.id}
                  type="button"
                  onClick={() => loadConversationMessages(conversation)}
                  className={cn(
                    "mb-1 w-full rounded-lg px-3 py-2.5 text-left transition-colors",
                    selected ? "bg-primary/10" : "hover:bg-muted/45"
                  )}
                >
                  <div className="flex items-center gap-2">
                    {meta.icon}
                    <span className="min-w-0 flex-1 truncate text-xs font-medium">
                      {conversation.title || "未命名会话"}
                    </span>
                  </div>
                  <div className="mt-1 flex items-center justify-between gap-2 text-[10px] text-muted-foreground">
                    <span>{meta.label}</span>
                    <span>{formatTime(conversation.lastMessageTime || conversation.createTime)}</span>
                  </div>
                </button>
              );
            })
          )}
        </div>
      </aside>

      <section className="flex min-w-0 flex-1 flex-col rounded-lg border border-border/35 bg-background/65">
        <header className="flex shrink-0 flex-wrap items-center justify-between gap-3 border-b border-border/25 px-4 py-3">
          <div className="flex min-w-0 items-center gap-3">
            <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-purple-500/10">
              <Bot className="h-4.5 w-4.5 text-purple-500" />
            </div>
            <div className="min-w-0">
              <h1 className="truncate text-base font-semibold">
                {activeConversation?.title || "AI 创作助手"}
              </h1>
              <div className="mt-0.5 flex items-center gap-2 text-xs text-muted-foreground">
                {statusMeta.icon}
                <span>{statusMeta.label}</span>
                {selectedProjectId !== "none" && (
                  <span className="truncate">
                    · {selectedProject?.name}
                  </span>
                )}
              </div>
            </div>
          </div>
          <div className="flex items-center gap-2">
            <Button
              variant="outline"
              size="sm"
              onClick={() => refreshAssistantData()}
              disabled={loadingConversations || loadingMessages || isStreaming}
            >
              <RefreshCw
                className={cn(
                  "h-3.5 w-3.5",
                  (loadingConversations || loadingMessages) && "animate-spin"
                )}
              />
              刷新
            </Button>
            {activeConversationDbId && (
              <Button variant="destructive" size="sm" onClick={handleDeleteConversation} disabled={isStreaming}>
                <Trash2 className="h-3.5 w-3.5" />
                删除
              </Button>
            )}
            <Button variant="outline" size="sm" onClick={startNewConversation} disabled={isStreaming}>
              <Plus className="h-3.5 w-3.5" />
              新会话
            </Button>
          </div>
        </header>

        <div ref={chatScrollRef} className="flex-1 overflow-y-auto px-4 py-4">
          {loadingMessages ? (
            <div className="flex h-full items-center justify-center">
              <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
            </div>
          ) : historyTurns.length === 0 && !pendingUserMessage && streamState.timeline.length === 0 ? (
            <EmptyAssistantState onPickPrompt={pickPrompt} />
          ) : (
            <div className="mx-auto max-w-4xl space-y-5">
              {historyTurns.map(renderHistoryTurn)}

              {pendingUserMessage && (
                <div className="space-y-3">
                  <div className="ml-auto max-w-[82%] rounded-lg bg-primary px-4 py-3 text-sm leading-6 text-primary-foreground">
                    {pendingUserMessage}
                  </div>
                  <div className="max-w-[92%] rounded-lg border border-border/30 bg-card/45 p-3">
                    {streamState.timeline.length > 0 ? (
                      <AgentPipelineTimeline timeline={streamState.timeline} isActive={isStreaming} />
                    ) : (
                      <div className="flex items-center gap-2 px-2 py-2 text-sm text-muted-foreground">
                        <Loader2 className="h-4 w-4 animate-spin" />
                        AI 正在思考...
                      </div>
                    )}
                    {error && (
                      <div className="mt-3 rounded-lg border border-destructive/25 bg-destructive/5 px-3 py-2 text-xs text-destructive">
                        {error}
                      </div>
                    )}
                  </div>
                </div>
              )}

              {!pendingUserMessage && error && (
                <div className="rounded-lg border border-destructive/25 bg-destructive/5 px-3 py-2 text-sm text-destructive">
                  {error}
                </div>
              )}
            </div>
          )}
        </div>

        <footer className="shrink-0 border-t border-border/25 p-3">
          <div className="mx-auto max-w-4xl">
            <div className="mb-2 lg:hidden">
              <select
                value={selectedProjectId}
                onChange={(event) => setSelectedProjectId(event.target.value)}
                disabled={isStreaming}
                className="h-9 w-full rounded-lg border border-border/40 bg-background px-2 text-xs outline-none"
              >
                <option value="none">全局工作区</option>
                {projects.map((project) => (
                  <option key={project.id} value={project.id}>
                    {project.name}
                  </option>
                ))}
              </select>
            </div>
            {historyTurns.length === 0 && !pendingUserMessage && (
              <div className="mb-2 flex flex-wrap gap-2">
                {suggestedPrompts.slice(0, 3).map((prompt) => (
                  <button
                    key={prompt}
                    type="button"
                    onClick={() => pickPrompt(prompt)}
                    className="rounded-full border border-border/35 px-3 py-1.5 text-xs text-muted-foreground transition-colors hover:border-primary/30 hover:text-foreground"
                  >
                    {prompt}
                  </button>
                ))}
              </div>
            )}
            <div className="flex items-end gap-2">
              <Textarea
                value={input}
                onChange={(event) => setInput(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === "Enter" && !event.shiftKey) {
                    event.preventDefault();
                    handleSend();
                  }
                }}
                disabled={isStreaming}
                placeholder="告诉 AI 你想聊什么：构思故事、润色对白、整理大纲、分析创意..."
                className="max-h-40 min-h-12 resize-none rounded-lg py-3 text-sm"
              />
              {isStreaming ? (
                <Button variant="outline" size="icon-lg" onClick={handleCancel} title="停止">
                  <Ban className="h-4 w-4" />
                </Button>
              ) : (
                <Button size="icon-lg" onClick={handleSend} disabled={!input.trim()}>
                  <Send className="h-4 w-4" />
                </Button>
              )}
            </div>
            <p className="mt-2 text-[11px] text-muted-foreground">
              Enter 发送，Shift + Enter 换行。全局工作区仅进行通用对话，选择具体项目后才会使用项目上下文。
            </p>
          </div>
        </footer>
      </section>
    </div>
  );
}
