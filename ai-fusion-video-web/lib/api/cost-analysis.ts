import { http } from "./client";

export type CostMediaType = "image" | "video";
export type CostBillingMode = "per_image" | "per_video" | "per_second" | "free";

export interface CostConfigRow {
  id: number | null;
  modelId: number;
  modelName: string;
  modelCode: string | null;
  modelType: number;
  mediaType: CostMediaType;
  billingMode: CostBillingMode;
  unitPrice: number;
  currency: string;
  enabled: boolean;
  configured: boolean;
  remark: string | null;
  updateTime: string | null;
}

export interface CostConfigUpdateItem {
  modelId: number;
  mediaType: CostMediaType;
  billingMode: CostBillingMode;
  unitPrice: number;
  currency?: string;
  enabled?: boolean;
  remark?: string | null;
}

export interface ModelCostBreakdown {
  modelId: number | null;
  modelName: string;
  modelCode: string | null;
  mediaType: CostMediaType;
  billingMode: CostBillingMode;
  unitPrice: number;
  currency: string;
  taskCount: number;
  successCount: number;
  successSeconds: number;
  unpricedCount: number;
  cost: number;
}

export interface ShotCostBreakdown {
  storyboardItemId: number;
  imageSuccessCount: number;
  videoSuccessCount: number;
  videoSuccessSeconds: number;
  unpricedImageCount: number;
  unpricedVideoCount: number;
  imageCost: number;
  videoCost: number;
  totalCost: number;
}

export interface ProjectCostSummary {
  projectId: number;
  totalCost: number;
  imageCost: number;
  videoCost: number;
  imageTaskCount: number;
  videoTaskCount: number;
  imageSuccessCount: number;
  videoSuccessCount: number;
  videoSuccessSeconds: number;
  unpricedImageCount: number;
  unpricedVideoCount: number;
  manualUploadVideoCount: number;
  costPerFinalSecond: number;
  modelCosts: ModelCostBreakdown[];
  shotCosts: ShotCostBreakdown[];
}

export const costAnalysisApi = {
  listConfigs: (mediaType?: CostMediaType) => {
    const query = mediaType ? `?mediaType=${mediaType}` : "";
    return http.get<never, CostConfigRow[]>(`/api/cost-analysis/configs${query}`);
  },

  batchUpdateConfigs: (items: CostConfigUpdateItem[]) =>
    http.put<never, number>("/api/cost-analysis/configs/batch", { items }),

  getProjectSummary: (projectId: number) =>
    http.get<never, ProjectCostSummary>(`/api/cost-analysis/projects/${projectId}/summary`),
};
