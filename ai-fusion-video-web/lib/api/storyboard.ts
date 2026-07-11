import { http } from "./client";

// ========== 类型定义 ==========

/** 分镜脚本 */
export interface Storyboard {
  id: number;
  projectId: number;
  scriptId: number | null;
  title: string;
  description: string | null;
  customColumns: string | null;
  scope: number;
  ownerType: number;
  ownerId: number;
  totalDuration: number | null;
  status: number;
  createTime: string;
  updateTime: string;
}

/** 分镜集合成状态: 0未开始 1合成中 2已完成 3失败 */
export type EpisodeComposeStatus = 0 | 1 | 2 | 3;

/** 分镜集 */
export interface StoryboardEpisode {
  id: number;
  storyboardId: number;
  episodeNumber: number | null;
  title: string | null;
  synopsis: string | null;
  sortOrder: number;
  status: number;
  composedVideoUrl: string | null;
  subtitleSrtUrl: string | null;
  subtitleAssUrl: string | null;
  composeStatus: EpisodeComposeStatus;
  composeErrorMsg: string | null;
  composedAt: string | null;
  createTime: string;
  updateTime: string;
}

/** 分镜场次 */
export interface StoryboardScene {
  id: number;
  episodeId: number;
  storyboardId: number;
  sceneNumber: string | null;
  sceneHeading: string | null;
  location: string | null;
  timeOfDay: string | null;
  intExt: string | null;
  sortOrder: number;
  status: number;
  composedVideoUrl: string | null;
  subtitleSrtUrl: string | null;
  subtitleAssUrl: string | null;
  composeStatus: EpisodeComposeStatus;
  composeErrorMsg: string | null;
  composedAt: string | null;
  createTime: string;
  updateTime: string;
}

/** 分镜条目 */
export interface StoryboardItem {
  id: number;
  storyboardId: number;
  storyboardEpisodeId: number | null;
  storyboardSceneId: number | null;
  sortOrder: number;
  shotNumber: string | null;
  autoShotNumber: string | null;
  imageUrl: string | null;
  referenceImageUrl: string | null;
  videoUrl: string | null;
  generatedImageUrl: string | null;
  generatedVideoUrl: string | null;
  videoPrompt: string | null;
  shotType: string | null;
  duration: number | null;
  content: string | null;
  sceneExpectation: string | null;
  sound: string | null;
  dialogue: string | null;
  soundEffect: string | null;
  music: string | null;
  cameraMovement: string | null;
  cameraAngle: string | null;
  cameraEquipment: string | null;
  focalLength: string | null;
  transition: string | null;
  characterIds: string | null;
  sceneAssetItemId: number | null;
  propIds: string | null;
  remark: string | null;
  customData: string | null;
  aiGenerated: boolean;
  status: number;
  createTime: string;
  updateTime: string;
}

/** 分镜镜头视频质检候选 */
export interface StoryboardVideoQuality {
  id: number;
  storyboardItemId: number;
  videoTaskId: number | null;
  videoItemId: number | null;
  videoUrl: string;
  coverUrl: string | null;
  promptSnapshot: string | null;
  totalScore: number;
  characterConsistencyScore: number;
  visualQualityScore: number;
  motionContinuityScore: number;
  audioReadinessScore: number;
  verdict: "pass" | "review" | "reject" | string;
  issueSummary: string | null;
  suggestion: string | null;
  selected: boolean;
  reviewerType: string | null;
  scoreDetails: string | null;
  createTime: string;
  updateTime: string;
}

/** 分镜镜头视频质检结果 */
export interface StoryboardVideoQualityReviewResult {
  storyboardItemId: number;
  candidates: StoryboardVideoQuality[];
  selectedCandidate: StoryboardVideoQuality | null;
  message: string;
}

// ========== 请求类型 ==========

/** 创建分镜请求 */
export interface StoryboardCreateReq {
  projectId: number;
  scriptId?: number;
  title: string;
  description?: string;
}

/** 更新分镜请求 */
export interface StoryboardUpdateReq {
  id: number;
  title?: string;
  description?: string;
  status?: number;
}

/** 创建分镜集请求 */
export interface StoryboardEpisodeCreateReq {
  storyboardId: number;
  episodeNumber?: number;
  title?: string;
  synopsis?: string;
  sortOrder?: number;
}

/** 更新分镜集请求 */
export interface StoryboardEpisodeUpdateReq {
  id: number;
  episodeNumber?: number;
  title?: string;
  synopsis?: string;
  sortOrder?: number;
}

/** Submit episode compose options */
export interface ComposeEpisodeVideoReq {
  generateSubtitleFiles?: boolean;
  burnSubtitles?: boolean;
  keepOriginalAudio?: boolean;
  originalAudioVolume?: number;
  bgmUrl?: string;
  bgmVolume?: number;
  clips?: Array<{
    itemId: number;
    sourceStart: number;
    duration: number;
  }>;
}

/** Create storyboard scene request */
export interface StoryboardSceneCreateReq {
  episodeId: number;
  storyboardId: number;
  sceneNumber?: string;
  sceneHeading?: string;
  location?: string;
  timeOfDay?: string;
  intExt?: string;
  sortOrder?: number;
}

/** 更新分镜场次请求 */
export interface StoryboardSceneUpdateReq {
  id: number;
  sceneNumber?: string;
  sceneHeading?: string;
  location?: string;
  timeOfDay?: string;
  intExt?: string;
  sortOrder?: number;
}

/** 创建分镜条目请求 */
export interface StoryboardItemCreateReq {
  storyboardId: number;
  storyboardEpisodeId?: number;
  storyboardSceneId?: number;
  shotNumber?: string;
  shotType?: string;
  content?: string;
  sceneExpectation?: string;
  sound?: string;
  dialogue?: string;
  soundEffect?: string;
  music?: string;
  cameraMovement?: string;
  cameraAngle?: string;
  transition?: string;
  duration?: number;
  sortOrder?: number;
  characterIds?: string | null;
  sceneAssetItemId?: number | null;
  propIds?: string | null;
}

/** 更新分镜条目请求 */
export interface StoryboardItemUpdateReq {
  id: number;
  shotNumber?: string;
  shotType?: string;
  content?: string;
  sceneExpectation?: string;
  sound?: string;
  dialogue?: string;
  soundEffect?: string;
  music?: string;
  cameraMovement?: string;
  cameraAngle?: string;
  transition?: string;
  duration?: number;
  sortOrder?: number;
  imageUrl?: string;
  videoPrompt?: string | null;
  status?: number;
  characterIds?: string | null;
  sceneAssetItemId?: number | null;
  propIds?: string | null;
}

/** 视频质检评分请求 */
export interface EvaluateStoryboardVideoQualityReq {
  autoSelect?: boolean;
  minScore?: number;
}

// ========== API ==========

export const storyboardApi = {
  // ========== 分镜脚本 ==========

  /** 获取分镜详情 */
  get: (id: number) => http.get<never, Storyboard>(`/api/storyboard/${id}`),

  /** 按项目查询分镜列表 */
  list: (projectId: number) =>
    http.get<never, Storyboard[]>(`/api/storyboard/list?projectId=${projectId}`),

  /** 创建分镜 */
  create: (data: StoryboardCreateReq) =>
    http.post<never, Storyboard>("/api/storyboard", data),

  /** 更新分镜 */
  update: (data: StoryboardUpdateReq) =>
    http.put<never, Storyboard>("/api/storyboard", data),

  /** 删除分镜 */
  delete: (id: number) => http.delete<never, boolean>(`/api/storyboard/${id}`),

  // ========== 分镜集 ==========

  /** 获取分镜集列表 */
  listEpisodes: (storyboardId: number) =>
    http.get<never, StoryboardEpisode[]>(`/api/storyboard/${storyboardId}/episodes`),

  /** 获取分镜集详情 */
  getEpisode: (id: number) =>
    http.get<never, StoryboardEpisode>(`/api/storyboard/episode/${id}`),

  /** 创建分镜集 */
  createEpisode: (data: StoryboardEpisodeCreateReq) =>
    http.post<never, StoryboardEpisode>("/api/storyboard/episode", data),

  /** 更新分镜集 */
  updateEpisode: (data: StoryboardEpisodeUpdateReq) =>
    http.put<never, StoryboardEpisode>("/api/storyboard/episode", data),

  /** 删除分镜集 */
  deleteEpisode: (id: number) =>
    http.delete<never, boolean>(`/api/storyboard/episode/${id}`),

  /** 提交本集合成视频任务（异步） */
  composeEpisodeVideo: (episodeId: number, options?: ComposeEpisodeVideoReq) =>
    http.post<never, string>(`/api/storyboard/episode/${episodeId}/compose-video`, options ?? {}),

  // ========== 分镜场次 ==========

  /** 按集获取分镜场次列表 */
  listScenesByEpisode: (episodeId: number) =>
    http.get<never, StoryboardScene[]>(`/api/storyboard/episode/${episodeId}/scenes`),

  /** 按分镜获取分镜场次列表 */
  listScenesByStoryboard: (storyboardId: number) =>
    http.get<never, StoryboardScene[]>(`/api/storyboard/${storyboardId}/scenes`),

  /** 获取分镜场次详情 */
  getScene: (id: number) =>
    http.get<never, StoryboardScene>(`/api/storyboard/scene/${id}`),

  composeSceneVideo: (sceneId: number, options?: ComposeEpisodeVideoReq) =>
    http.post<never, string>(`/api/storyboard/scene/${sceneId}/compose-video`, options ?? {}),

  /** 创建分镜场次 */
  createScene: (data: StoryboardSceneCreateReq) =>
    http.post<never, StoryboardScene>("/api/storyboard/scene", data),

  /** 更新分镜场次 */
  updateScene: (data: StoryboardSceneUpdateReq) =>
    http.put<never, StoryboardScene>("/api/storyboard/scene", data),

  /** 删除分镜场次 */
  deleteScene: (id: number) =>
    http.delete<never, boolean>(`/api/storyboard/scene/${id}`),

  // ========== 分镜条目 ==========

  /** 获取分镜条目列表（按分镜） */
  listItems: (storyboardId: number) =>
    http.get<never, StoryboardItem[]>(`/api/storyboard/${storyboardId}/items`),

  /** 获取分镜条目列表（按场次） */
  listItemsByScene: (sceneId: number) =>
    http.get<never, StoryboardItem[]>(`/api/storyboard/scene/${sceneId}/items`),

  /** 获取分镜条目详情 */
  getItem: (id: number) =>
    http.get<never, StoryboardItem>(`/api/storyboard/item/${id}`),

  /** 创建分镜条目 */
  createItem: (data: StoryboardItemCreateReq) =>
    http.post<never, StoryboardItem>("/api/storyboard/item", data),

  /** 更新分镜条目 */
  updateItem: (data: StoryboardItemUpdateReq) =>
    http.put<never, StoryboardItem>("/api/storyboard/item", data),

  /** 上传本地视频并关联到分镜镜头 */
  uploadItemVideo: (itemId: number, file: File) => {
    const formData = new FormData();
    formData.append("file", file);
    return http.post<never, StoryboardItem>(
      `/api/storyboard/item/${itemId}/upload-video`,
      formData,
      {
        headers: { "Content-Type": "multipart/form-data" },
        timeout: 10 * 60 * 1000,
      }
    );
  },

  /** 获取镜头视频质检候选 */
  listItemVideoQuality: (itemId: number) =>
    http.get<never, StoryboardVideoQuality[]>(
      `/api/storyboard/item/${itemId}/video-quality`
    ),

  /** 执行镜头视频质检评分 */
  evaluateItemVideoQuality: (
    itemId: number,
    options?: EvaluateStoryboardVideoQualityReq
  ) =>
    http.post<never, StoryboardVideoQualityReviewResult>(
      `/api/storyboard/item/${itemId}/video-quality/evaluate`,
      options ?? {}
    ),

  /** 选用镜头视频候选 */
  selectItemVideoCandidate: (itemId: number, qualityId: number) =>
    http.post<never, StoryboardVideoQuality>(
      `/api/storyboard/item/${itemId}/video-quality/${qualityId}/select`
    ),

  /** 删除分镜条目 */
  deleteItem: (id: number) =>
    http.delete<never, boolean>(`/api/storyboard/item/${id}`),

  /** 批量创建分镜条目 */
  batchCreateItems: (storyboardId: number, items: StoryboardItemCreateReq[]) =>
    http.post<never, boolean>(`/api/storyboard/${storyboardId}/items/batch`, items),

  /** 批量更新分镜条目排序 */
  batchUpdateItemSort: (ids: number[]) =>
    http.post<never, boolean>("/api/storyboard/items/batch-sort", { ids }),
};
