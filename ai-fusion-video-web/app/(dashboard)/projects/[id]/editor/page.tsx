"use client";

import { useEffect, useMemo, useState } from "react";
import { useParams, useRouter, useSearchParams } from "next/navigation";
import { ArrowLeft, Captions, Clapperboard, GripVertical, Loader2, Music, Play, RotateCcw, Save, Scissors, Trash2, Upload, Volume2 } from "lucide-react";
import { DndContext, PointerSensor, useSensor, useSensors, type DragEndEvent } from "@dnd-kit/core";
import { SortableContext, arrayMove, horizontalListSortingStrategy, useSortable } from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { storyboardApi, type StoryboardEpisode, type StoryboardItem } from "@/lib/api/storyboard";
import { resolveMediaUrl } from "@/lib/api/client";

type Clip = { id: string; itemId: number; title: string; url: string; sourceStart: number; duration: number };
type Settings = { burnSubtitles: boolean; keepOriginalAudio: boolean; originalAudioVolume: number; bgmUrl: string; bgmVolume: number };
type Draft = { version: 1; clips: Clip[]; settings: Settings; updatedAt: string };
const defaults: Settings = { burnSubtitles: true, keepOriginalAudio: true, originalAudioVolume: 1, bgmUrl: "", bgmVolume: .25 };

function TimelineClip({ clip, active, select }: { clip: Clip; active: boolean; select: () => void }) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({ id: clip.id });
  return <button ref={setNodeRef} type="button" onClick={select}
    style={{ transform: CSS.Transform.toString(transform), transition, width: Math.max(132, clip.duration * 28) }}
    className={`relative h-20 shrink-0 overflow-hidden rounded-md border text-left ${active ? "border-primary ring-2 ring-primary/20" : "border-border"} ${isDragging ? "z-20 opacity-70" : ""}`}>
    <video src={resolveMediaUrl(clip.url) || undefined} muted preload="metadata" className="absolute inset-0 h-full w-full object-cover opacity-55" />
    <span {...attributes} {...listeners} className="absolute left-1 top-1 cursor-grab rounded bg-black/70 p-1 text-white"><GripVertical className="size-3.5" /></span>
    <span className="absolute inset-x-0 bottom-0 bg-black/75 px-2 py-1 text-xs text-white"><span className="block truncate">{clip.title}</span><span className="text-white/70">{clip.duration.toFixed(1)}s</span></span>
  </button>;
}

export default function VideoEditorPage() {
  const params = useParams<{ id: string }>(); const search = useSearchParams(); const router = useRouter();
  const episodeId = Number(search.get("episodeId")); const storageKey = `afv-editor:${params.id}:${episodeId}`;
  const [episode, setEpisode] = useState<StoryboardEpisode | null>(null); const [clips, setClips] = useState<Clip[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null); const [settings, setSettings] = useState<Settings>(defaults);
  const [loading, setLoading] = useState(true); const [exporting, setExporting] = useState(false); const [savedAt, setSavedAt] = useState<string>();
  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 5 } }));
  const selected = clips.find(c => c.id === selectedId) || clips[0] || null;
  const total = useMemo(() => clips.reduce((sum, c) => sum + c.duration, 0), [clips]);

  useEffect(() => { if (!episodeId) { setLoading(false); return; } void (async () => {
    try { const [ep, scenes] = await Promise.all([storyboardApi.getEpisode(episodeId), storyboardApi.listScenesByEpisode(episodeId)]);
      const groups = await Promise.all(scenes.sort((a,b) => a.sortOrder-b.sortOrder).map(s => storyboardApi.listItemsByScene(s.id)));
      const initial = groups.flat().filter(i => i.videoUrl || i.generatedVideoUrl).map(toClip);
      const raw = localStorage.getItem(storageKey); const draft = raw ? JSON.parse(raw) as Draft : null;
      setEpisode(ep); setClips(draft?.clips || initial); setSettings(draft?.settings || defaults); setSavedAt(draft?.updatedAt); setSelectedId((draft?.clips || initial)[0]?.id || null);
    } finally { setLoading(false); }
  })(); }, [episodeId, storageKey]);

  function save() { const updatedAt = new Date().toISOString(); localStorage.setItem(storageKey, JSON.stringify({ version: 1, clips, settings, updatedAt } satisfies Draft)); setSavedAt(updatedAt); }
  function dragEnd({ active, over }: DragEndEvent) { if (!over || active.id === over.id) return; setClips(v => arrayMove(v, v.findIndex(c => c.id === active.id), v.findIndex(c => c.id === over.id))); }
  function patch(p: Partial<Clip>) { if (selected) setClips(v => v.map(c => c.id === selected.id ? { ...c, ...p } : c)); }
  async function exportVideo() { setExporting(true); save(); try { await storyboardApi.composeEpisodeVideo(episodeId, { generateSubtitleFiles: true, ...settings }); router.push(`/projects/${params.id}/storyboards`); } finally { setExporting(false); } }

  if (loading) return <div className="flex h-[70vh] items-center justify-center"><Loader2 className="size-6 animate-spin" /></div>;
  if (!episodeId) return <div className="p-8 text-sm text-muted-foreground">缺少有效的 episodeId。</div>;
  return <div className="flex min-h-[calc(100vh-4rem)] flex-col bg-background">
    <header className="flex h-14 items-center gap-3 border-b px-4"><Button variant="ghost" size="icon" title="返回分镜" onClick={() => router.push(`/projects/${params.id}/storyboards`)}><ArrowLeft /></Button><div className="min-w-0"><h1 className="truncate text-sm font-semibold">{episode?.title || `第 ${episode?.episodeNumber || "-"} 集`} · 在线剪辑</h1><p className="text-xs text-muted-foreground">{clips.length} 个片段 · {total.toFixed(1)} 秒{savedAt ? ` · 已保存 ${new Date(savedAt).toLocaleTimeString()}` : ""}</p></div><div className="ml-auto flex gap-2"><Button variant="outline" onClick={save}><Save />保存草稿</Button><Button disabled={exporting || !clips.length} onClick={exportVideo}><Upload />{exporting ? "提交中" : "导出视频"}</Button></div></header>
    <main className="grid min-h-0 flex-1 grid-cols-1 lg:grid-cols-[220px_minmax(0,1fr)_280px]">
      <aside className="hidden border-r p-3 lg:block"><h2 className="mb-3 flex items-center gap-2 text-sm font-medium"><Clapperboard className="size-4" />素材</h2><div className="space-y-2">{clips.map(c => <button key={c.id} onClick={() => setSelectedId(c.id)} className="flex w-full items-center gap-2 rounded-md border p-2 text-left hover:bg-muted"><Play className="size-3.5"/><span className="truncate text-xs">{c.title}</span></button>)}</div></aside>
      <section className="flex min-w-0 flex-col bg-neutral-950 p-4"><div className="flex flex-1 items-center justify-center"><video key={selected?.url} src={selected ? resolveMediaUrl(selected.url) || undefined : undefined} controls className="max-h-[58vh] max-w-full bg-black shadow-2xl" /></div><p className="pt-3 text-center text-xs text-neutral-400">{selected ? `${selected.title} · ${selected.sourceStart.toFixed(1)}s - ${(selected.sourceStart + selected.duration).toFixed(1)}s` : "时间线中暂无视频"}</p></section>
      <aside className="border-l p-4"><h2 className="mb-4 text-sm font-semibold">片段属性</h2>{selected && <div className="space-y-4"><label className="block text-xs text-muted-foreground">名称<Input className="mt-1" value={selected.title} onChange={e => patch({title:e.target.value})}/></label><div className="grid grid-cols-2 gap-2"><label className="text-xs text-muted-foreground">入点（秒）<Input className="mt-1" type="number" min="0" step=".1" value={selected.sourceStart} onChange={e => patch({sourceStart:Math.max(0,+e.target.value)})}/></label><label className="text-xs text-muted-foreground">时长（秒）<Input className="mt-1" type="number" min=".1" step=".1" value={selected.duration} onChange={e => patch({duration:Math.max(.1,+e.target.value)})}/></label></div><Button variant="destructive" className="w-full" onClick={() => { setClips(v => v.filter(c => c.id !== selected.id)); setSelectedId(null); }}><Trash2 />移除片段</Button></div>}
        <div className="my-5 border-t"/><h2 className="mb-3 text-sm font-semibold">成片设置</h2><div className="space-y-3 text-xs"><label className="flex justify-between"><span className="flex gap-2"><Captions className="size-4"/>烧录字幕</span><input type="checkbox" checked={settings.burnSubtitles} onChange={e => setSettings(s=>({...s,burnSubtitles:e.target.checked}))}/></label><label className="flex justify-between"><span className="flex gap-2"><Volume2 className="size-4"/>保留原声</span><input type="checkbox" checked={settings.keepOriginalAudio} onChange={e => setSettings(s=>({...s,keepOriginalAudio:e.target.checked}))}/></label><label className="block text-muted-foreground">原声音量<input className="mt-2 w-full" type="range" min="0" max="2" step=".05" value={settings.originalAudioVolume} onChange={e=>setSettings(s=>({...s,originalAudioVolume:+e.target.value}))}/></label><label className="block text-muted-foreground"><span className="flex gap-2"><Music className="size-4"/>BGM URL</span><Input className="mt-1" value={settings.bgmUrl} onChange={e=>setSettings(s=>({...s,bgmUrl:e.target.value}))}/></label></div>
      </aside>
    </main>
    <footer className="border-t bg-muted/20 p-3"><div className="mb-2 flex items-center gap-2 text-xs font-medium"><Scissors className="size-4"/>视频轨<span className="text-muted-foreground">拖动片段调整顺序 · 当前导出仅应用声音与字幕设置</span><Button className="ml-auto" variant="ghost" size="sm" onClick={() => { localStorage.removeItem(storageKey); location.reload(); }}><RotateCcw />重置</Button></div><DndContext sensors={sensors} onDragEnd={dragEnd}><SortableContext items={clips.map(c=>c.id)} strategy={horizontalListSortingStrategy}><div className="flex min-h-20 gap-2 overflow-x-auto pb-1">{clips.map(c=><TimelineClip key={c.id} clip={c} active={selected?.id===c.id} select={()=>setSelectedId(c.id)}/>)}</div></SortableContext></DndContext></footer>
  </div>;
}

function toClip(item: StoryboardItem, index: number): Clip { return { id:`item-${item.id}`, itemId:item.id, title:item.shotNumber || item.autoShotNumber || `镜头 ${index+1}`, url:item.videoUrl || item.generatedVideoUrl || "", sourceStart:0, duration:Math.max(.1,item.duration || 5) }; }
