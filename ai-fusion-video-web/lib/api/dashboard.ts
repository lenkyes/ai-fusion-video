import { http } from "./client";
import type { PageResult } from "./types";

export type GenerationTaskType = "image" | "video";

export interface DashboardOverview {
  projectCount: number;
  scriptCount: number;
  storyboardCount: number;
  storyboardItemCount: number;
  generatedImageShotCount: number;
  generatedVideoShotCount: number;
  assetCount: number;
  imageTaskCount: number;
  videoTaskCount: number;
  queuedTaskCount: number;
  runningTaskCount: number;
  failedTaskCount: number;
  completionRate: number;
}

export interface GenerationKindStats {
  type: GenerationTaskType;
  label: string;
  total: number;
  queued: number;
  running: number;
  completed: number;
  failed: number;
  outputCount: number;
  averageCompletedSeconds: number | null;
}

export interface QueueSnapshot {
  type: GenerationTaskType;
  queueName: string;
  modelId: number | null;
  modelName: string | null;
  pendingCount: number;
  runningCount: number;
  maxConcurrent: number;
}

export interface DailyActivity {
  date: string;
  imageTasks: number;
  videoTasks: number;
  failedTasks: number;
}

export interface GenerationTask {
  type: GenerationTaskType;
  id: number;
  taskId: string;
  projectId: number | null;
  modelId: number | null;
  modelName: string | null;
  prompt: string | null;
  status: number | null;
  errorMsg: string | null;
  category: string | null;
  count: number | null;
  successCount: number | null;
  createTime: string | null;
  updateTime: string | null;
  canRetry: boolean;
  durationSeconds: number | null;
  queueName: string | null;
}

export interface DashboardAnalytics {
  overview: DashboardOverview;
  generationStats: GenerationKindStats[];
  queues: QueueSnapshot[];
  dailyActivity: DailyActivity[];
  recentTasks: GenerationTask[];
}

export interface RecoverTasksResp {
  imageRecovered: number;
  videoRecovered: number;
  totalRecovered: number;
}

export const dashboardApi = {
  analytics: () => http.get<never, DashboardAnalytics>("/api/dashboard/analytics"),

  tasks: (params: {
    pageNo?: number;
    pageSize?: number;
    type?: "all" | GenerationTaskType;
    status?: number | "all";
  }) => {
    const search = new URLSearchParams();
    search.set("pageNo", String(params.pageNo ?? 1));
    search.set("pageSize", String(params.pageSize ?? 20));
    if (params.type && params.type !== "all") search.set("type", params.type);
    if (params.status !== undefined && params.status !== "all") {
      search.set("status", String(params.status));
    }
    return http.get<never, PageResult<GenerationTask>>(`/api/dashboard/tasks?${search.toString()}`);
  },

  queues: () => http.get<never, QueueSnapshot[]>("/api/dashboard/queues"),

  retryTask: (type: GenerationTaskType, id: number) =>
    http.post<never, { type: GenerationTaskType; id: number; taskId: string }>(
      `/api/dashboard/tasks/${type}/${id}/retry`
    ),

  recoverStaleTasks: () =>
    http.post<never, RecoverTasksResp>("/api/dashboard/tasks/recover-stale"),
};
