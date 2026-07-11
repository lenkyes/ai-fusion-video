import { http } from "./client";
import {
  clearStoredAuth,
  isTerminalAuthError,
  refreshAuthTokens,
} from "@/lib/auth-session";

// ========== 类型定义 ==========

export interface AiChatReq {
  message?: string;
  conversationId?: string;
  modelId?: number;
  agentType?: string;
  category?: string;
  /** 自定义对话标题（不传则使用消息前50字） */
  title?: string;
  projectId?: number;
  context?: Record<string, unknown>;
  systemPrompt?: string;
  instruction?: string;
  enabledTools?: string[];
  enableParallelTools?: boolean;
  /** 当前页面上下文引用（type + id），用于模板变量替换 */
  autoReferences?: Array<{ type: string; id: number }>;
}

export type OutputType =
  | "REASONING"
  | "CONTENT"
  | "TOOL_CALL"
  | "TOOL_FINISHED"
  | "SUB_AGENT_FINISHED"
  | "DONE"
  | "ERROR"
  | "CANCELLED";

export interface ToolCallInfo {
  id: string;
  name: string;
  arguments: string;
}

export interface AiChatStreamEvent {
  messageId?: string;
  conversationId?: string;
  outputType: OutputType;
  content?: string;
  reasoningContent?: string;
  reasoningStartTime?: number;
  reasoningDurationMs?: number;
  toolCalls?: ToolCallInfo[];
  toolCallId?: string;
  toolName?: string;
  toolResult?: string;
  toolStatus?: string;
  finished?: boolean;
  error?: string;
  /** 子 Agent 事件关联的父级工具调用 ID */
  parentToolCallId?: string;
  /** 事件来源 Agent 名称（null 表示主 Agent） */
  agentName?: string;
}

// ========== SSE 回调 ==========

export interface StreamCallbacks {
  onEvent: (event: AiChatStreamEvent) => void;
  onError?: (error: Error) => void;
  onComplete?: () => void;
}

// ========== SSE 流式专用工具函数 ==========

/**
 * 从 localStorage 读取 token（仅供 SSE 流式 fetch 使用）
 */
function getToken(): string | null {
  if (typeof window === "undefined") return null;
  try {
    const stored = localStorage.getItem("auth-storage");
    if (stored) {
      const parsed = JSON.parse(stored);
      return parsed?.state?.token ?? null;
    }
  } catch {
    // 忽略
  }
  return null;
}

// ---- SSE 刷新令牌队列机制（与 client.ts 对齐） ----
/**
 * 尝试刷新 token（带队列，避免并发刷新）
 * 仅供 SSE 流式 fetch 使用
 */
async function refreshTokenForSSE(): Promise<string> {
  const result = await refreshAuthTokens();
  const { useAuthStore } = await import("@/lib/store/auth-store");
  useAuthStore.getState().setTokens(result.accessToken, result.refreshToken);
  return result.accessToken;
}

/**
 * 带自动 token 刷新的 fetch 包装（供 SSE 流式请求使用）
 * 当收到 401 时自动刷新 token 并重试一次，支持并发请求排队
 */
export async function authenticatedFetch(
  input: RequestInfo | URL,
  init?: RequestInit
): Promise<Response> {
  const token = getToken();
  const headers = new Headers(init?.headers);
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }

  const response = await fetch(input, { ...init, headers });

  if (response.status === 401) {
    try {
      const newToken = await refreshTokenForSSE();
      headers.set("Authorization", `Bearer ${newToken}`);
      return fetch(input, { ...init, headers });
    } catch (error) {
      if (isTerminalAuthError(error) && typeof window !== "undefined") {
        clearStoredAuth();
        if (window.location.pathname !== "/login") {
          window.location.href = "/login";
        }
      }
    }
  }

  return response;
}

// ========== 对话历史 API ==========

export interface AgentConversation {
  id: number;
  conversationId: string;
  userId: number;
  projectId: number;
  contextType?: string;
  agentType?: string;
  category?: string;
  contextId?: number;
  title: string;
  messageCount: number;
  lastMessageTime?: string;
  status: string;
  createTime?: string;
}

export interface AgentMessage {
  id: number;
  conversationId: string;
  role: string;
  content: string;
  referencesJson?: string;
  toolName?: string;
  toolStatus?: string;
  /** 工具调用 ID（关联同一次调用的发起和结果） */
  toolCallId?: string;
  /** 父级工具调用 ID（子 Agent 事件归属） */
  parentToolCallId?: string;
  reasoningContent?: string;
  reasoningDurationMs?: number;
  messageOrder: number;
  createTime?: string;
}

export interface PageResult<T> {
  list: T[];
  total: number;
}

/**
 * 获取对话列表（分页）
 */
export async function listConversations(params: {
  pageNo: number;
  pageSize: number;
  category?: string;
}): Promise<PageResult<AgentConversation>> {
  const searchParams = new URLSearchParams({
    pageNo: String(params.pageNo),
    pageSize: String(params.pageSize),
  });
  if (params.category) {
    searchParams.set("category", params.category);
  }
  return http.get(`/api/ai/assistant/conversations?${searchParams}`);
}

/**
 * 获取对话消息列表
 */
export async function listMessages(
  conversationId: string
): Promise<AgentMessage[]> {
  return http.get(
    `/api/ai/assistant/conversations/${encodeURIComponent(conversationId)}/messages`
  );
}

/**
 * 删除对话
 */
export async function deleteConversation(id: number): Promise<void> {
  await http.delete(`/api/ai/assistant/conversations/${id}`);
}

