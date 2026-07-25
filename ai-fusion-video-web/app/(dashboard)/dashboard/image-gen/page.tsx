"use client";
import { useEffect, useRef, useState } from "react";
import { Download, ImageIcon, Loader2, Plus, RefreshCw, WandSparkles } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { aiModelApi, type AiModel } from "@/lib/api/ai-model";
import { imageGenerationApi, type ImageItem } from "@/lib/api/image-generation";
import { resolveMediaUrl } from "@/lib/api/client";
import { ThumbImage } from "@/components/dashboard/generation-media-result";
import { ReferenceImageList } from "@/components/dashboard/reference-image-list";
import { useAuthStore } from "@/lib/store/auth-store";

type Job = { id: string; prompt: string; refs: string[]; status: "loading" | "done" | "error"; images: ImageItem[]; error?: string };
type Session = { id: string; title: string; jobs: Job[] };

export default function ImageGenPage() {
  const userId = useAuthStore((s) => s.user?.id ?? "guest");
  const storageKey = `image-gen-v3:${userId}`;
  const [hydrated, setHydrated] = useState(false);
  const [sessions, setSessions] = useState<Session[]>([]);
  const [activeId, setActiveId] = useState("");
  const [prompt, setPrompt] = useState("");
  const [refs, setRefs] = useState<string[]>([]);
  const [model, setModel] = useState<AiModel | null>(null);
  const promptRef = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    try {
      const saved = JSON.parse(localStorage.getItem(storageKey) || "[]") as Session[];
      if (saved.length) { setSessions(saved); setActiveId(saved[0].id); }
      else { const session = { id: crypto.randomUUID(), title: "新会话", jobs: [] }; setSessions([session]); setActiveId(session.id); }
    } finally { setHydrated(true); }
  }, [storageKey]);
  useEffect(() => { if (hydrated) localStorage.setItem(storageKey, JSON.stringify(sessions)); }, [hydrated, sessions, storageKey]);
  useEffect(() => { aiModelApi.listByType(2).then((list) => setModel(list.find((item) => item.defaultModel) ?? list[0] ?? null)); }, []);

  const active = sessions.find((item) => item.id === activeId);
  const updateSession = (id: string, fn: (session: Session) => Session) => setSessions((list) => list.map((item) => item.id === id ? fn(item) : item));
  const newSession = () => { const session = { id: crypto.randomUUID(), title: "新会话", jobs: [] }; setSessions((list) => [session, ...list]); setActiveId(session.id); setPrompt(""); setRefs([]); };
  const reuse = (job: Job) => { setPrompt(job.prompt); setRefs(job.refs.length ? [...job.refs] : []); promptRef.current?.focus(); promptRef.current?.scrollIntoView({ behavior: "smooth", block: "center" }); };

  const generate = () => {
    const text = prompt.trim(); if (!text || !model || !active) return;
    const sessionId = active.id; const job: Job = { id: crypto.randomUUID(), prompt: text, refs: refs.filter(Boolean), status: "loading", images: [] };
    updateSession(sessionId, (session) => ({ ...session, title: session.title === "新会话" ? text.slice(0, 24) : session.title, jobs: [job, ...session.jobs] }));
    setPrompt("");
    void (async () => {
      try {
        const taskId = await imageGenerationApi.submit({ prompt: text, modelId: model.id, refImageUrls: job.refs.length ? JSON.stringify(job.refs) : undefined, count: 1 });
        for (let attempt = 0; attempt < 90; attempt += 1) {
          const task = await imageGenerationApi.get(taskId);
          if (task.status === 2) { const images = await imageGenerationApi.items(task.id); updateSession(sessionId, (session) => ({ ...session, jobs: session.jobs.map((item) => item.id === job.id ? { ...item, status: "done", images } : item) })); return; }
          if (task.status === 3) throw new Error(task.errorMsg || "生成失败");
          await new Promise((resolve) => setTimeout(resolve, 2000));
        }
        throw new Error("生成超时，请稍后查看任务中心");
      } catch (error) { updateSession(sessionId, (session) => ({ ...session, jobs: session.jobs.map((item) => item.id === job.id ? { ...item, status: "error", error: error instanceof Error ? error.message : "生成失败" } : item) })); }
    })();
  };

  if (!hydrated) return <div className="flex h-96 items-center justify-center"><Loader2 className="animate-spin" /></div>;
  return <div className="flex h-[calc(100vh-7rem)] min-h-[620px] gap-4 pb-4">
    <aside className="hidden w-64 shrink-0 rounded-2xl border bg-card/50 p-3 lg:block"><div className="mb-3 flex items-center justify-between"><div><b>创作会话</b><p className="text-xs text-muted-foreground">自动保存历史</p></div><Button size="icon-sm" variant="outline" onClick={newSession}><Plus /></Button></div>{sessions.map((session) => <button key={session.id} onClick={() => setActiveId(session.id)} className={`mb-1 w-full rounded-xl px-3 py-2.5 text-left ${session.id === activeId ? "bg-primary/10 ring-1 ring-primary/20" : "hover:bg-muted/50"}`}><div className="truncate text-sm font-medium">{session.title}</div><small className="text-muted-foreground">{session.jobs.reduce((count, job) => count + job.images.length, 0)} 张图片 · {session.jobs.length} 次生成</small></button>)}</aside>
    <main className="flex min-w-0 flex-1 flex-col overflow-hidden rounded-2xl border bg-background/70"><header className="flex items-center justify-between border-b bg-card/30 p-4"><div><h1 className="flex items-center gap-2 text-xl font-semibold"><WandSparkles className="text-pink-500" />AI 生图工作台</h1><p className="text-xs text-muted-foreground">文生图 · 图生图 · 并行创作</p></div><Button variant="outline" onClick={newSession}><Plus className="mr-1 h-4 w-4" />新会话</Button></header>
      <div className="flex-1 overflow-y-auto p-5"><div className="mx-auto max-w-5xl space-y-5">{active?.jobs.map((job) => <article key={job.id} className="rounded-2xl border bg-card/35 p-4 shadow-sm"><div className="mb-3 flex items-start justify-between gap-4"><div><p className="text-xs font-medium text-muted-foreground">生成提示词</p><p className="mt-1 text-sm leading-6">{job.prompt}</p></div><Button size="sm" variant="outline" onClick={() => reuse(job)}><RefreshCw className="mr-1 h-3.5 w-3.5" />再次创作</Button></div>{job.status === "loading" && <div className="flex h-64 flex-col items-center justify-center rounded-xl border border-dashed bg-muted/20"><Loader2 className="mb-3 h-7 w-7 animate-spin text-primary" /><p className="text-sm font-medium">正在生成图片</p><p className="text-xs text-muted-foreground">可以继续提交新的创作</p></div>}{job.status === "error" && <div className="rounded-xl bg-destructive/8 p-4 text-sm text-destructive">{job.error}</div>}{job.status === "done" && <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">{job.images.map((item, index) => { const src = resolveMediaUrl(item.imageUrl || item.thumbnailUrl); return src ? <div key={index}><ThumbImage src={src} label={`作品 ${index + 1}`} previewClassName="h-64 w-full" /><a href={src} download className="mt-2 inline-flex items-center text-xs text-primary"><Download className="mr-1 h-3 w-3" />下载原图</a></div> : null; })}</div>}</article>)}{!active?.jobs.length && <div className="flex h-72 flex-col items-center justify-center text-muted-foreground"><ImageIcon className="mb-3 h-10 w-10 opacity-30" /><p>描述你的创意，开始第一张作品</p></div>}</div></div>
      <footer className="border-t bg-card/40 p-4"><div className="mx-auto max-w-5xl space-y-3"><Textarea ref={promptRef} value={prompt} onChange={(event) => setPrompt(event.target.value)} placeholder="描述你想生成的画面…" className="min-h-24 rounded-xl" /><ReferenceImageList value={refs} onChange={setRefs} /><div className="flex items-center justify-between"><span className="text-xs text-muted-foreground">当前模型：{model?.name || "加载中"}</span><Button onClick={generate} disabled={!model || !prompt.trim()}><WandSparkles className="mr-1 h-4 w-4" />生成图片</Button></div></div></footer>
    </main></div>;
}
