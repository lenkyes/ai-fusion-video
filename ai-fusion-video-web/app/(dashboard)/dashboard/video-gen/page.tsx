"use client";
import { useCallback, useEffect, useRef, useState } from "react";
import {
  CircleAlert,
  Clapperboard,
  Download,
  Loader2,
  Plus,
  RefreshCw,
  RotateCcw,
} from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";
import { aiModelApi, type AiModel } from "@/lib/api/ai-model";
import { dashboardApi } from "@/lib/api/dashboard";
import {
  videoGenerationApi,
  type VideoGenerationSession,
  type VideoItem,
  type VideoModelCapability,
  type VideoTask,
} from "@/lib/api/video-generation";
import { resolveMediaUrl } from "@/lib/api/client";
import { ReferenceImageList } from "@/components/dashboard/reference-image-list";

interface SubmissionSnapshot {
  sessionId: number;
  prompt: string;
  modelId: number;
  referenceImages: string[];
  duration: number;
  resolution?: string;
  ratio?: string;
}

type VideoJob = VideoTask & {
  items: VideoItem[];
  clientKey: string;
  submission?: SubmissionSnapshot;
};

export default function VideoGenPage() {
  const [sessions, setSessions] = useState<VideoGenerationSession[]>([]),
    [active, setActive] = useState<number>(),
    [jobs, setJobs] = useState<VideoJob[]>([]),
    [prompt, setPrompt] = useState(""),
    [refs, setRefs] = useState<string[]>([]),
    [model, setModel] = useState<AiModel | null>(null),
    [capability, setCapability] = useState<VideoModelCapability | null>(null),
    [durationInput, setDurationInput] = useState("5"),
    [resolution, setResolution] = useState(""),
    [ratio, setRatio] = useState(""),
    [loading, setLoading] = useState(true);
  const loadVersion = useRef(0);
  const activeRef = useRef<number | undefined>(undefined);
  const temporaryId = useRef(-1);
  const loadJobs = useCallback(async (sessionId: number) => {
    const version = ++loadVersion.current;
    setLoading(true);
    try {
      const page = await videoGenerationApi.page(sessionId);
      const withItems = await Promise.all(
        page.list.map(async (t) => ({
          ...t,
          items: await videoGenerationApi.items(t.id),
          clientKey: `task-${t.id}`,
        })),
      );
      if (version === loadVersion.current) setJobs(withItems);
    } finally {
      if (version === loadVersion.current) setLoading(false);
    }
  }, []);
  useEffect(() => {
    void (async () => {
      let ss = await videoGenerationApi.sessions();
      let selectedId = ss[0]?.id;
      if (!selectedId) {
        selectedId = await videoGenerationApi.createSession("新会话");
        ss = await videoGenerationApi.sessions();
      }
      setSessions(ss);
      setActive(selectedId);
    })();
    aiModelApi
      .listByType(3)
      .then((x) => setModel(x.find((m) => m.defaultModel) || x[0] || null));
  }, []);
  const clampDuration = useCallback(
    (value: number, cap: VideoModelCapability | null) => {
      let v = Number.isFinite(value)
        ? Math.round(value)
        : (cap?.defaultDuration ?? 5);
      if (cap?.minDuration != null && v < cap.minDuration) v = cap.minDuration;
      if (cap?.maxDuration != null && v > cap.maxDuration) v = cap.maxDuration;
      return Math.max(v, 1);
    },
    [],
  );
  useEffect(() => {
    if (!model) return;
    let cancelled = false;
    videoGenerationApi
      .capability(model.id)
      .then((cap) => {
        if (cancelled) return;
        const resolutions = (cap.supportedResolutions ?? []).filter(Boolean);
        const aspectRatios = (cap.supportedAspectRatios ?? []).filter(Boolean);
        setCapability(cap);
        setDurationInput(String(clampDuration(cap.defaultDuration ?? 5, cap)));
        setResolution((current) =>
          resolutions.includes(current) ? current : (resolutions[0] ?? ""),
        );
        setRatio((current) =>
          aspectRatios.includes(current) ? current : (aspectRatios[0] ?? ""),
        );
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [model, clampDuration]);
  useEffect(() => {
    activeRef.current = active;
    if (active) void loadJobs(active);
  }, [active, loadJobs]);
  const newSession = async () => {
    const id = await videoGenerationApi.createSession("新会话");
    const ss = await videoGenerationApi.sessions();
    setSessions(ss);
    setActive(id);
    setJobs([]);
  };

  const updateJob = useCallback(
    (clientKey: string, update: (job: VideoJob) => VideoJob) => {
      setJobs((current) =>
        current.map((job) => (job.clientKey === clientKey ? update(job) : job)),
      );
    },
    [],
  );

  // 视频生成远慢于图片，轮询窗口放宽到约 15 分钟
  const pollTask = useCallback(
    async (taskId: string, sessionId: number, clientKey: string) => {
      for (let i = 0; i < 300; i++) {
        try {
          const task = await videoGenerationApi.get(taskId);
          const items =
            task.status === 2 ? await videoGenerationApi.items(task.id) : [];
          if (activeRef.current === sessionId) {
            updateJob(clientKey, () => ({
              ...task,
              items,
              clientKey,
            }));
          }
          if (task.status === 2 || task.status === 3) return;
        } catch {
          // A temporary polling failure should not turn a persisted task into a failed task.
        }
        await new Promise((resolve) => setTimeout(resolve, 3000));
      }
    },
    [updateJob],
  );

  const submitSnapshot = useCallback(
    async (snapshot: SubmissionSnapshot, clientKey: string) => {
      updateJob(clientKey, (job) => ({
        ...job,
        status: 0,
        errorMsg: undefined,
        items: [],
      }));
      try {
        const taskId = await videoGenerationApi.submit({
          sessionId: snapshot.sessionId,
          prompt: snapshot.prompt,
          modelId: snapshot.modelId,
          generateMode: snapshot.referenceImages.length
            ? "image2video"
            : "text2video",
          referenceImageUrls: snapshot.referenceImages.length
            ? JSON.stringify(snapshot.referenceImages)
            : undefined,
          duration: snapshot.duration,
          resolution: snapshot.resolution || undefined,
          ratio: snapshot.ratio || undefined,
          count: 1,
        });
        await pollTask(taskId, snapshot.sessionId, clientKey);
      } catch (error) {
        if (activeRef.current === snapshot.sessionId) {
          updateJob(clientKey, (job) => ({
            ...job,
            status: 3,
            errorMsg: error instanceof Error ? error.message : "任务提交失败",
            submission: snapshot,
          }));
        }
      }
    },
    [pollTask, updateJob],
  );

  const generate = () => {
    if (!prompt.trim() || !model || !active) return;
    const duration = clampDuration(Number(durationInput), capability);
    setDurationInput(String(duration));
    const snapshot: SubmissionSnapshot = {
      sessionId: active,
      prompt: prompt.trim(),
      modelId: model.id,
      referenceImages: refs.filter(Boolean),
      duration,
      resolution: resolution || undefined,
      ratio: ratio || undefined,
    };
    const id = temporaryId.current--;
    const clientKey = `pending-${Math.abs(id)}`;
    const pendingJob: VideoJob = {
      id,
      taskId: clientKey,
      sessionId: snapshot.sessionId,
      prompt: snapshot.prompt,
      referenceImageUrls: snapshot.referenceImages.length
        ? JSON.stringify(snapshot.referenceImages)
        : undefined,
      status: 0,
      items: [],
      clientKey,
      submission: snapshot,
    };
    setJobs((current) => [pendingJob, ...current]);
    setPrompt("");
    setRefs([]);
    void submitSnapshot(snapshot, clientKey);
  };

  const retry = async (job: VideoJob) => {
    if (job.id < 0 && job.submission) {
      await submitSnapshot(job.submission, job.clientKey);
      return;
    }
    updateJob(job.clientKey, (current) => ({
      ...current,
      status: 0,
      errorMsg: undefined,
      items: [],
    }));
    try {
      const result = await dashboardApi.retryTask("video", job.id);
      await pollTask(result.taskId, job.sessionId ?? activeRef.current!, job.clientKey);
    } catch (error) {
      updateJob(job.clientKey, (current) => ({
        ...current,
        status: 3,
        errorMsg: error instanceof Error ? error.message : "重试失败",
      }));
      toast.error(error instanceof Error ? error.message : "重试失败");
    }
  };
  const reuse = (job: VideoTask) => {
    setPrompt(job.prompt);
    try {
      setRefs(job.referenceImageUrls ? JSON.parse(job.referenceImageUrls) : []);
    } catch {
      setRefs([]);
    }
    if (job.duration != null)
      setDurationInput(String(clampDuration(job.duration, capability)));
    if (job.resolution && capability?.supportedResolutions?.includes(job.resolution))
      setResolution(job.resolution);
    if (job.ratio && capability?.supportedAspectRatios?.includes(job.ratio))
      setRatio(job.ratio);
  };
  if (loading)
    return (
      <div className="flex h-96 items-center justify-center">
        <Loader2 className="animate-spin" />
      </div>
    );
  return (
    <div className="flex h-[calc(100vh-7rem)] min-h-[620px] gap-4 pb-4">
      <aside className="hidden w-64 rounded-2xl border p-3 lg:block">
        <div className="mb-3 flex justify-between">
          <b>历史会话</b>
          <Button size="icon-sm" variant="outline" onClick={newSession}>
            <Plus />
          </Button>
        </div>
        {sessions.map((s) => (
          <button
            key={s.id}
            onClick={() => setActive(s.id)}
            className={`mb-1 w-full rounded-lg px-3 py-2 text-left ${s.id === active ? "bg-primary/10" : "hover:bg-muted/40"}`}
          >
            {s.title}
          </button>
        ))}
      </aside>
      <main className="flex min-w-0 flex-1 flex-col rounded-2xl border">
        <header className="flex items-center justify-between border-b p-4">
          <div>
            <h1 className="flex items-center gap-2 text-xl font-semibold">
              <Clapperboard className="text-cyan-500" />
              AI 视频
            </h1>
            <p className="text-xs text-muted-foreground">
              数据库会话 · {jobs.length} 次生成
            </p>
          </div>
          <Button variant="outline" onClick={newSession}>
            <Plus />
            新会话
          </Button>
        </header>
        <div className="flex-1 overflow-y-auto p-5">
          <div className="mx-auto max-w-5xl space-y-5">
            {jobs.map((job) => (
              <article key={job.clientKey} className="rounded-xl border p-4">
                <div className="mb-3 flex justify-between">
                  <p className="text-sm leading-6">{job.prompt}</p>
                  {job.status === 2 && (
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() => reuse(job)}
                    >
                      <RefreshCw />
                      再次创作
                    </Button>
                  )}
                </div>
                {(job.status === 0 || job.status === 1) && (
                  <div className="flex h-48 flex-col items-center justify-center gap-3 rounded-lg bg-muted/20 text-muted-foreground">
                    <Loader2 className="size-6 animate-spin" />
                    <span className="text-sm">
                      {job.status === 0
                        ? "正在排队生成"
                        : "正在生成视频，可能需要几分钟"}
                    </span>
                  </div>
                )}
                {job.status === 3 && (
                  <div className="flex min-h-36 flex-col items-center justify-center gap-3 rounded-lg border border-destructive/20 bg-destructive/5 px-4 text-center">
                    <CircleAlert className="size-7 text-destructive" />
                    <p className="text-sm text-destructive">
                      {job.errorMsg || "生成失败"}
                    </p>
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() => void retry(job)}
                    >
                      <RotateCcw />
                      重试
                    </Button>
                  </div>
                )}
                {job.status === 2 && (
                  <div className="grid gap-4 sm:grid-cols-2">
                    {job.items.map((item, i) => {
                      const src = resolveMediaUrl(item.videoUrl);
                      const poster = resolveMediaUrl(item.coverUrl);
                      return src ? (
                        <div key={i} className="space-y-1.5">
                          <video
                            src={src}
                            poster={poster || undefined}
                            controls
                            className="w-full rounded-xl border border-border/50 bg-muted/20"
                          />
                          <a
                            href={src}
                            download
                            className="text-xs text-primary"
                          >
                            <Download className="mr-1 inline h-3 w-3" />
                            下载
                          </a>
                        </div>
                      ) : null;
                    })}
                  </div>
                )}
              </article>
            ))}
          </div>
        </div>
        <footer className="space-y-3 border-t p-4">
          <Textarea
            value={prompt}
            onChange={(e) => setPrompt(e.target.value)}
            placeholder="描述你想生成的视频画面…"
          />
          <ReferenceImageList value={refs} onChange={setRefs} />
          <div className="flex flex-wrap items-end gap-3">
            <div className="space-y-1">
              <p className="text-xs text-muted-foreground">
                时长（秒）
                {capability?.minDuration != null ||
                capability?.maxDuration != null
                  ? ` · ${capability?.minDuration ?? 1} ~ ${capability?.maxDuration ?? "∞"}`
                  : ""}
              </p>
              <Input
                type="number"
                className="w-24"
                min={capability?.minDuration ?? 1}
                max={capability?.maxDuration}
                value={durationInput}
                onChange={(e) => setDurationInput(e.target.value)}
                onBlur={() =>
                  setDurationInput(
                    String(clampDuration(Number(durationInput), capability)),
                  )
                }
              />
            </div>
            {capability?.supportedResolutions?.length ? (
              <div className="space-y-1">
                <p className="text-xs text-muted-foreground">分辨率</p>
                <Select
                  value={resolution}
                  onValueChange={(v) => setResolution(v ?? "")}
                  items={capability.supportedResolutions.map((r) => ({
                    value: r,
                    label: r,
                  }))}
                >
                  <SelectTrigger className="w-36 text-xs">
                    <SelectValue placeholder="选择分辨率" />
                  </SelectTrigger>
                  <SelectContent className="text-xs">
                    <SelectGroup>
                      {capability.supportedResolutions.map((r) => (
                        <SelectItem key={r} value={r} className="text-xs">
                          {r}
                        </SelectItem>
                      ))}
                    </SelectGroup>
                  </SelectContent>
                </Select>
              </div>
            ) : null}
            {capability?.supportedAspectRatios?.length ? (
              <div className="space-y-1">
                <p className="text-xs text-muted-foreground">宽高比</p>
                <Select
                  value={ratio}
                  onValueChange={(v) => setRatio(v ?? "")}
                  items={capability.supportedAspectRatios.map((r) => ({
                    value: r,
                    label: r,
                  }))}
                >
                  <SelectTrigger className="w-28 text-xs">
                    <SelectValue placeholder="选择宽高比" />
                  </SelectTrigger>
                  <SelectContent className="text-xs">
                    <SelectGroup>
                      {capability.supportedAspectRatios.map((r) => (
                        <SelectItem key={r} value={r} className="text-xs">
                          {r}
                        </SelectItem>
                      ))}
                    </SelectGroup>
                  </SelectContent>
                </Select>
              </div>
            ) : null}
          </div>
          <div className="flex justify-between">
            <span className="text-xs text-muted-foreground">
              当前模型：{model?.name || "加载中"}
            </span>
            <Button onClick={generate} disabled={!model || !prompt.trim()}>
              <Clapperboard />
              生成视频
            </Button>
          </div>
        </footer>
      </main>
    </div>
  );
}
