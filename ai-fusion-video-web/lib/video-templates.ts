export type VideoTemplateBeat = {
  id: string;
  start: number;
  end: number;
  label: string;
  purpose: string;
  shotGuidance: string;
  voiceoverGuidance: string;
};

export type VideoTemplate = {
  id: string;
  version: number;
  name: string;
  category: string;
  description: string;
  duration: number;
  aspectRatio: "9:16" | "16:9" | "1:1";
  storyPrompt: string;
  negativePrompt: string[];
  camera: {
    perspective: string;
    device: string;
    style: string[];
  };
  protagonist?: {
    role: "camera_holder" | "visible_character";
    visualAssetPolicy: "none" | "partial_only" | "standard";
    allowedVisibility: string[];
    forbidStandardCharacterPortrait: boolean;
  };
  voiceover: {
    enabled: boolean;
    person: "first" | "third";
    tone: string;
    maxSentenceLength: number;
    bgmDuckDb: number;
  };
  audio: {
    bgmUrl?: string;
    bgmVolume: number;
    originalAudioVolume: number;
  };
  beats: VideoTemplateBeat[];
};

export const VIDEO_TEMPLATES: VideoTemplate[] = [
  {
    id: "pov-lyrical-memory",
    version: 1,
    name: "第一人称 · 抒情回忆",
    category: "情绪叙事",
    description: "手持手机主观镜头、克制念白，情绪随尾奏逐步递进。",
    duration: 60,
    aspectRatio: "9:16",
    storyPrompt: "围绕用户主题创作一个真实、克制的第一人称生活故事。用具体动作和物件承载情绪，不解释道理，不写鸡汤。故事必须有关系、遗憾、一次情绪转折和余韵。念白使用短句和自然停顿，最重要的一句放在音乐高潮前后。输出按模板节拍拆分的故事、念白和 POV 镜头提示词。",
    negativePrompt: ["第三人称全景", "航拍", "棚拍", "广告片构图", "拍摄者完整正脸", "过度稳定的电影运镜", "说教式总结"],
    camera: {
      perspective: "first_person_pov",
      device: "handheld_phone",
      style: ["自然轻微晃动", "偶发自动对焦", "真实环境光", "行走与呼吸感", "普通生活细节"],
    },
    protagonist: {
      role: "camera_holder",
      visualAssetPolicy: "partial_only",
      allowedVisibility: ["手部", "腿部", "局部倒影", "模糊影子"],
      forbidStandardCharacterPortrait: true,
    },
    voiceover: { enabled: true, person: "first", tone: "克制、口语化、像深夜回忆", maxSentenceLength: 18, bgmDuckDb: -6 },
    audio: { bgmUrl: undefined, bgmVolume: .34, originalAudioVolume: .18 },
    beats: [
      { id: "open", start: 0, end: 8, label: "记忆入口", purpose: "用一个动作或物件建立人物关系", shotGuidance: "推门、低头看手、窗外或旧物，慢速手持", voiceoverGuidance: "一到两句，不交代完整背景" },
      { id: "setup", start: 8, end: 22, label: "生活片段", purpose: "给出具体相处细节", shotGuidance: "2-4 个日常 POV 镜头，保留环境声", voiceoverGuidance: "口语短句，描述当时不理解的细节" },
      { id: "rise", start: 22, end: 38, label: "遗憾递进", purpose: "揭示没有说出口的情绪", shotGuidance: "行走、回头、空座位或离开的交通工具", voiceoverGuidance: "逐渐靠近核心遗憾，仍不直接总结" },
      { id: "climax", start: 38, end: 50, label: "情绪高潮", purpose: "完成转折并给出最重要的一句话", shotGuidance: "关键物件特写与主观回望，镜头可略微缩短", voiceoverGuidance: "只保留最有力量的 1-2 句，随后留白" },
      { id: "outro", start: 50, end: 60, label: "尾奏余韵", purpose: "让音乐承担情绪并克制收束", shotGuidance: "背影之外的主观空镜、窗外或逐渐远离", voiceoverGuidance: "可无念白；最多一句开放式收尾" },
    ],
  },
];

export function getVideoTemplate(id?: string | null) {
  return VIDEO_TEMPLATES.find(template => template.id === id) ?? null;
}

export function buildTemplateGenerationPrompt(template: VideoTemplate, storySeed: string) {
  const beatPlan = template.beats.map(beat =>
    `${beat.start}-${beat.end}秒 ${beat.label}：${beat.purpose}；镜头：${beat.shotGuidance}；念白：${beat.voiceoverGuidance}`
  ).join("\n");
  const protagonistRule = template.protagonist?.role === "camera_holder"
    ? `【POV 主角强制规则】故事中的“我”是摄像机持有者和第一人称叙事者，不是普通出镜角色。不得为“我”创建标准人物立绘、正脸参考图或全身角色资产；资产规划时不要把“我”传给角色资产创建工具。剧本场次不得写“主角站在画面中”等第三人称描述，必须写成摄像机所见和我的主观动作。画面最多允许出现：${template.protagonist.allowedVisibility.join("、")}。其他被“我”观察的人物可以正常创建角色资产。念白统一标记为 VO（我）。`
    : "";
  return `【视频模板】${template.name}\n【故事主题】${storySeed.trim() || "请根据用户后续输入确定"}\n【总时长】${template.duration}秒，${template.aspectRatio}\n【创作规则】${template.storyPrompt}\n${protagonistRule}\n【镜头风格】${template.camera.style.join("、")}\n【禁止】${template.negativePrompt.join("、")}\n【念白】${template.voiceover.person === "first" ? "第一人称" : "第三人称"}，${template.voiceover.tone}，单句不超过${template.voiceover.maxSentenceLength}字\n【节拍结构】\n${beatPlan}`;
}

export function buildRandomTemplateStoryPrompt(template: VideoTemplate) {
  return buildTemplateGenerationPrompt(template,
    "请自行随机创作一个全新的故事。不要询问用户主题；避免复用常见示例、人物关系、地点、关键物件和反转。每次生成都应更换人物、生活处境与情绪触发事件。"
  );
}

export type VideoTemplateRecord = {
  id: number; code: string; name: string; category: string; description: string;
  coverUrl: string | null; configJson: string; version: number; status: number;
  sortOrder: number; createTime: string; updateTime: string;
};

export type EditableVideoTemplate = Omit<VideoTemplateRecord, "id" | "version" | "createTime" | "updateTime">;

export function parseTemplateRecord(record: VideoTemplateRecord): VideoTemplate {
  return { ...(JSON.parse(record.configJson) as Omit<VideoTemplate, "id" | "version" | "name" | "category" | "description">),
    id: record.code, version: record.version, name: record.name, category: record.category, description: record.description };
}

export const videoTemplateApi = {
  listRecords: (publishedOnly = true) => http.get<never, VideoTemplateRecord[]>(`/api/video-template/list?publishedOnly=${publishedOnly}`),
  list: async (publishedOnly = true) => (await videoTemplateApi.listRecords(publishedOnly)).map(parseTemplateRecord),
  create: (value: EditableVideoTemplate) => http.post<never, VideoTemplateRecord>("/api/video-template", value),
  update: (id: number, value: EditableVideoTemplate) => http.put<never, VideoTemplateRecord>(`/api/video-template/${id}`, value),
  delete: (id: number) => http.delete<never, boolean>(`/api/video-template/${id}`),
};
import { http } from "@/lib/api/client";
