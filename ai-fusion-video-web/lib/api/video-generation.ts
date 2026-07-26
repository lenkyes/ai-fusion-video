import { http } from "./client";
export interface VideoTask {
  id: number;
  taskId: string;
  sessionId?: number;
  prompt: string;
  generateMode?: string;
  referenceImageUrls?: string;
  duration?: number;
  resolution?: string;
  ratio?: string;
  status: number;
  errorMsg?: string;
}
export interface VideoModelCapability {
  configured: boolean;
  modelId?: number;
  modelName?: string;
  supportedAspectRatios?: string[];
  supportedResolutions?: string[];
  minDuration?: number;
  maxDuration?: number;
  defaultDuration?: number;
}
export interface VideoItem {
  videoUrl?: string;
  coverUrl?: string;
  duration?: number;
  status: number;
}
export interface VideoGenerationSession {
  id: number;
  userId: number;
  title: string;
  createTime: string;
  updateTime: string;
}
export const videoGenerationApi = {
  page: (sessionId: number) =>
    http.get<never, { list: VideoTask[]; total: number }>(
      `/api/generation/video/page?pageNo=1&pageSize=100&category=dashboard_video_gen&sessionId=${sessionId}`,
    ),
  submit: (data: Record<string, unknown>) =>
    http.post<never, string>("/api/generation/video/submit", {
      category: "dashboard_video_gen",
      ...data,
    }),
  get: (id: string) =>
    http.get<never, VideoTask>(
      `/api/generation/video/${encodeURIComponent(id)}`,
    ),
  items: (id: number) =>
    http.get<never, VideoItem[]>(`/api/generation/video/${id}/items`),
  capability: (modelId?: number) =>
    http.get<never, VideoModelCapability>(
      `/api/generation/video/capability${modelId ? `?modelId=${modelId}` : ""}`,
    ),
  sessions: () =>
    http.get<never, VideoGenerationSession[]>("/api/generation/video/sessions"),
  createSession: (title: string) =>
    http.post<never, number>("/api/generation/video/sessions", { title }),
};
