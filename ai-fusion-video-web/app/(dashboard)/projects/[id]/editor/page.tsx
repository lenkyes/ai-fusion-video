"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useParams, useRouter, useSearchParams } from "next/navigation";
import {
  ArrowLeft, Captions, ChevronDown, Clapperboard, Copy, Film, Image as ImageIcon, Layers3,
  Loader2, Lock, LockOpen, Menu, Mic2, Music2, Pause, Play, Plus, Redo2,
  RotateCcw, Save, Scissors, Search, Settings2, SlidersHorizontal, Sparkles,
  Trash2, Undo2, Upload, Volume2, VolumeX, ZoomIn, ZoomOut,
} from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { storyboardApi, type StoryboardEpisode, type StoryboardItem } from "@/lib/api/storyboard";
import { resolveMediaUrl } from "@/lib/api/client";
import { uploadAudio, uploadVideo } from "@/lib/api/storage";
import { getVideoTemplate, type VideoTemplate } from "@/lib/video-templates";
import { useProject } from "../project-context";

type Clip = { id: string; itemId: number; title: string; url: string; sourceStart: number; duration: number; trackId: string; opacity?: number; filter?: string; stickers?: string[]; keyframes?: Array<{ time: number; x: number; y: number; scale: number }> };
type Track = { id: string; name: string; type: "video" | "overlay" | "audio"; muted: boolean; locked: boolean; volume?: number };
type Settings = { burnSubtitles: boolean; keepOriginalAudio: boolean; originalAudioVolume: number; bgmUrl: string; bgmVolume: number; fadeInDuration: number; fadeOutDuration: number };
type Draft = { version: 2; clips: Clip[]; tracks?: Track[]; settings: Settings; templateId?: string; storySeed?: string; updatedAt: string };
type InspectorTab = "clip" | "audio" | "export";

const defaults: Settings = { burnSubtitles: true, keepOriginalAudio: true, originalAudioVolume: 1, bgmUrl: "", bgmVolume: .25, fadeInDuration: 0, fadeOutDuration: 0 };
const baseTrack: Track = { id: "video-1", name: "主视频", type: "video", muted: false, locked: false };
const clipColors: Record<Track["type"], string> = { video: "bg-sky-500/25 border-sky-400/45", overlay: "bg-fuchsia-500/20 border-fuchsia-400/40", audio: "bg-emerald-500/20 border-emerald-400/40" };

function formatTime(seconds: number) {
  const value = Math.max(0, seconds);
  const minutes = Math.floor(value / 60);
  const secs = Math.floor(value % 60);
  const frames = Math.floor((value % 1) * 25);
  return `${String(minutes).padStart(2, "0")}:${String(secs).padStart(2, "0")}:${String(frames).padStart(2, "0")}`;
}

export default function VideoEditorPage() {
  const params = useParams<{ id: string }>();
  const searchParams = useSearchParams();
  const router = useRouter();
  const { project } = useProject();
  const episodeId = Number(searchParams.get("episodeId"));
  const storageKey = `afv-editor:${params.id}:${episodeId}`;
  const previewRef = useRef<HTMLVideoElement>(null);
  const timelineScrollRef = useRef<HTMLDivElement>(null);
  const mediaInputRef = useRef<HTMLInputElement>(null);
  const audioInputRef = useRef<HTMLInputElement>(null);
  const playTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const playheadTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const scheduledAudioTimersRef = useRef<Array<ReturnType<typeof setTimeout>>>([]);
  const playingAudioRef = useRef<Array<{ audio: HTMLAudioElement; trackId: string; baseVolume: number }>>([]);
  const videoBaseVolumeRef = useRef(1);
  const playbackSessionRef = useRef(0);

  const [episode, setEpisode] = useState<StoryboardEpisode | null>(null);
  const [clips, setClips] = useState<Clip[]>([]);
  const [tracks, setTracks] = useState<Track[]>([baseTrack]);
  const [settings, setSettings] = useState<Settings>(defaults);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [exporting, setExporting] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [savedAt, setSavedAt] = useState<string>();
  const [playing, setPlaying] = useState(false);
  const [playhead, setPlayhead] = useState(0);
  const [scale, setScale] = useState(42);
  const [search, setSearch] = useState("");
  const [mediaTab, setMediaTab] = useState<"media" | "text" | "audio">("media");
  const [inspectorTab, setInspectorTab] = useState<InspectorTab>("clip");
  const [uploadTrackId, setUploadTrackId] = useState<string>();
  const [targetTrackId, setTargetTrackId] = useState("video-1");
  const [undoStack, setUndoStack] = useState<Clip[][]>([]);
  const [redoStack, setRedoStack] = useState<Clip[][]>([]);

  const selected = clips.find(clip => clip.id === selectedId) ?? null;
  const selectedTrack = selected ? tracks.find(track => track.id === selected.trackId) : undefined;
  const total = useMemo(() => Math.max(1, ...tracks.map(track => clips.filter(clip => clip.trackId === track.id).reduce((sum, clip) => sum + clip.duration, 0))), [clips, tracks]);
  const timelineWidth = Math.max(800, Math.ceil(total + 5) * scale);
  const activeTemplate = (project?.properties?.videoTemplateSnapshot as VideoTemplate | undefined) || getVideoTemplate(typeof project?.properties?.videoTemplateId === "string" ? project.properties.videoTemplateId : "");
  const mediaClips = useMemo(() => {
    const unique = new Map<string, Clip>();
    clips.forEach(clip => {
      const key = clip.itemId > 0 ? `item:${clip.itemId}` : `url:${clip.url}`;
      if (!unique.has(key)) unique.set(key, clip);
    });
    return [...unique.values()];
  }, [clips]);
  const filteredClips = mediaClips.filter(clip => clip.title.toLowerCase().includes(search.toLowerCase()));

  function readMediaDuration(url: string, kind: "audio" | "video") {
    return new Promise<number>((resolve) => {
      const media = document.createElement(kind);
      media.preload = "metadata";
      media.onloadedmetadata = () => { resolve(Number.isFinite(media.duration) && media.duration > 0 ? media.duration : 5); media.remove(); };
      media.onerror = () => { resolve(5); media.remove(); };
      media.src = resolveMediaUrl(url) || url;
    });
  }

  useEffect(() => {
    if (!episodeId) { setLoading(false); return; }
    void (async () => {
      try {
        const [ep, scenes] = await Promise.all([storyboardApi.getEpisode(episodeId), storyboardApi.listScenesByEpisode(episodeId)]);
        const groups = await Promise.all(scenes.sort((a, b) => a.sortOrder - b.sortOrder).map(scene => storyboardApi.listItemsByScene(scene.id)));
        const initial = groups.flat().filter(item => item.videoUrl || item.generatedVideoUrl).map(toClip);
        const raw = localStorage.getItem(storageKey);
        const draft = raw ? JSON.parse(raw) as Draft : null;
        const templateSettings = activeTemplate ? { ...defaults, bgmUrl: activeTemplate.audio.bgmUrl || "", bgmVolume: activeTemplate.audio.bgmVolume, originalAudioVolume: activeTemplate.audio.originalAudioVolume } : defaults;
        const nextClips = draft?.clips || initial;
        setEpisode(ep); setClips(nextClips); setTracks(draft?.tracks?.length ? draft.tracks : [baseTrack]);
        setSettings(draft?.settings ? { ...templateSettings, ...draft.settings } : templateSettings); setSavedAt(draft?.updatedAt); setSelectedId(nextClips[0]?.id || null);
      } catch { toast.error("编辑工程加载失败"); } finally { setLoading(false); }
    })();
  }, [episodeId, storageKey, activeTemplate]);

  const commit = useCallback((next: Clip[]) => {
    setUndoStack(stack => [...stack.slice(-29), clips]); setRedoStack([]); setClips(next);
  }, [clips]);
  const patchSelected = (patch: Partial<Clip>) => selected && commit(clips.map(clip => clip.id === selected.id ? { ...clip, ...patch } : clip));
  const save = useCallback(() => {
    const updatedAt = new Date().toISOString();
    localStorage.setItem(storageKey, JSON.stringify({ version: 2, clips, tracks, settings, updatedAt } satisfies Draft));
    setSavedAt(updatedAt); toast.success("草稿已保存");
  }, [clips, settings, storageKey, tracks]);

  const stop = useCallback(() => {
    playbackSessionRef.current += 1;
    if (playTimerRef.current) clearTimeout(playTimerRef.current);
    if (playheadTimerRef.current) clearInterval(playheadTimerRef.current);
    scheduledAudioTimersRef.current.forEach(timer => clearTimeout(timer));
    scheduledAudioTimersRef.current = [];
    if (previewRef.current) {
      previewRef.current.onloadedmetadata = null;
      previewRef.current.onended = null;
      previewRef.current.pause();
      previewRef.current.style.opacity = "1";
    }
    playingAudioRef.current.forEach(({ audio }) => { audio.pause(); audio.src = ""; });
    playingAudioRef.current = [];
    setPlaying(false);
  }, []);
  const play = useCallback(() => {
    if (playing) { stop(); return; }
    const sessionId = ++playbackSessionRef.current;
    const videoTrack = tracks.find(track => track.type === "video" && !track.muted && clips.some(clip => clip.trackId === track.id && clip.url));
    const sequence = videoTrack ? clips.filter(clip => clip.trackId === videoTrack.id && clip.url) : [];
    if (!sequence.length || !previewRef.current) return;
    const videoTrackVolume = videoTrack?.volume ?? 1;
    const startPosition = playhead >= total ? 0 : playhead;
    let elapsed = 0;
    let index = sequence.findIndex(clip => {
      const contains = startPosition < elapsed + clip.duration;
      if (!contains) elapsed += clip.duration;
      return contains;
    });
    if (index < 0) { index = 0; elapsed = 0; }
    const startIndex = index;
    const initialClipOffset = Math.max(0, startPosition - elapsed);
    const startedAt = performance.now();

    const startAudio = (source: { url: string; start: number; volume: number; trackId: string }) => {
      if (playbackSessionRef.current !== sessionId) return;
      const audio = new Audio(resolveMediaUrl(source.url) || source.url);
      audio.volume = Math.min(1, Math.max(0, source.volume));
      audio.addEventListener("loadedmetadata", () => {
        if (playbackSessionRef.current !== sessionId) return;
        audio.currentTime = Math.min(source.start, Math.max(0, audio.duration - .01));
        void audio.play().catch(() => toast.error("音频播放失败，请检查媒体地址"));
      }, { once: true });
      audio.load();
      playingAudioRef.current.push({ audio, trackId: source.trackId, baseVolume: Math.min(1, Math.max(0, source.volume)) });
    };
    tracks.filter(track => track.type === "audio" && !track.muted).forEach(track => {
      let trackOffset = 0;
      clips.filter(clip => clip.trackId === track.id && clip.url).forEach(clip => {
        const clipStart = trackOffset;
        const clipEnd = clipStart + clip.duration;
        trackOffset = clipEnd;
        if (clipEnd <= startPosition) return;
        const delay = Math.max(0, clipStart - startPosition);
        const sourceOffset = clip.sourceStart + Math.max(0, startPosition - clipStart);
        if (delay === 0) startAudio({ url: clip.url, start: sourceOffset, volume: track.volume ?? 1, trackId: track.id });
        else scheduledAudioTimersRef.current.push(setTimeout(() => startAudio({ url: clip.url, start: sourceOffset, volume: track.volume ?? 1, trackId: track.id }), delay * 1000));
      });
    });
    if (settings.bgmUrl) startAudio({ url: settings.bgmUrl, start: startPosition, volume: settings.bgmVolume, trackId: "bgm" });

    const playNext = () => {
      if (playbackSessionRef.current !== sessionId) return;
      const clip = sequence[index];
      if (!clip || !previewRef.current) { setPlayhead(0); stop(); return; }
      const video = previewRef.current;
      video.src = resolveMediaUrl(clip.url) || "";
      video.muted = !settings.keepOriginalAudio || tracks.find(track => track.id === clip.trackId)?.muted === true;
      video.volume = Math.min(1, Math.max(0, settings.originalAudioVolume * videoTrackVolume));
      videoBaseVolumeRef.current = video.volume;
      setSelectedId(clip.id);
      const offsetInClip = index === startIndex ? initialClipOffset : 0;
      setPlayhead(elapsed + offsetInClip);
      video.onloadedmetadata = () => {
        if (playbackSessionRef.current !== sessionId) return;
        video.currentTime = Math.min(clip.sourceStart + offsetInClip, Math.max(0, video.duration - .01));
        void video.play().then(() => {
          if (playbackSessionRef.current !== sessionId) { video.pause(); return; }
          const remaining = clip.duration - offsetInClip;
          playTimerRef.current = setTimeout(() => { elapsed += clip.duration; index += 1; playNext(); }, remaining * 1000);
        }).catch(() => { stop(); toast.error("视频播放失败，请检查媒体地址或格式"); });
      };
      video.load();
    };
    setPlaying(true);
    playheadTimerRef.current = setInterval(() => {
      const position = Math.min(total, startPosition + (performance.now() - startedAt) / 1000);
      const fadeIn = settings.fadeInDuration > 0 ? Math.min(1, position / settings.fadeInDuration) : 1;
      const fadeOut = settings.fadeOutDuration > 0 ? Math.min(1, Math.max(0, total - position) / settings.fadeOutDuration) : 1;
      const fade = Math.min(fadeIn, fadeOut);
      if (previewRef.current) { previewRef.current.style.opacity = String(fade); previewRef.current.volume = videoBaseVolumeRef.current * fade; }
      playingAudioRef.current.forEach(item => { item.audio.volume = item.baseVolume * fade; });
      setPlayhead(position);
    }, 100);
    playNext();
  }, [clips, playhead, playing, settings, stop, total, tracks]);

  useEffect(() => () => stop(), [stop]);

  useEffect(() => {
    const container = timelineScrollRef.current;
    if (!playing || !container) return;
    const playheadX = 144 + playhead * scale;
    const visibleLeft = container.scrollLeft + 144;
    const visibleRight = container.scrollLeft + container.clientWidth;
    const followMargin = Math.min(180, container.clientWidth * .25);
    if (playheadX > visibleRight - followMargin) {
      container.scrollLeft = Math.max(0, playheadX - container.clientWidth + followMargin);
    } else if (playheadX < visibleLeft) {
      container.scrollLeft = Math.max(0, playheadX - 144);
    }
  }, [playhead, playing, scale]);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      const tag = (event.target as HTMLElement)?.tagName;
      if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT") return;
      if (event.code === "Space") { event.preventDefault(); play(); }
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "s") { event.preventDefault(); save(); }
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "z") { event.preventDefault(); undo(); }
      if ((event.key === "Delete" || event.key === "Backspace") && selected) removeSelected();
    };
    window.addEventListener("keydown", onKey); return () => window.removeEventListener("keydown", onKey);
  });

  function undo() { const previous = undoStack.at(-1); if (!previous) return; setRedoStack(stack => [...stack, clips]); setUndoStack(stack => stack.slice(0, -1)); setClips(previous); }
  function redo() { const next = redoStack.at(-1); if (!next) return; setUndoStack(stack => [...stack, clips]); setRedoStack(stack => stack.slice(0, -1)); setClips(next); }
  function removeSelected() { if (!selected) return; commit(clips.filter(clip => clip.id !== selected.id)); setSelectedId(null); }
  function selectClip(id: string) { stop(); setSelectedId(id); }
  function removeClip(id: string) { stop(); commit(clips.filter(clip => clip.id !== id)); if (selectedId === id) setSelectedId(null); }
  function beginScrub(event: React.PointerEvent) {
    event.preventDefault(); stop();
    const startX = event.clientX;
    const startTime = playhead;
    const move = (moveEvent: PointerEvent) => setPlayhead(Math.min(total, Math.max(0, startTime + (moveEvent.clientX - startX) / scale)));
    const up = () => { window.removeEventListener("pointermove", move); window.removeEventListener("pointerup", up); };
    window.addEventListener("pointermove", move); window.addEventListener("pointerup", up, { once: true });
  }
  function duplicate() { if (!selected) return; const copy = { ...selected, id: `${selected.id}-${Date.now()}`, title: `${selected.title} 副本` }; const index = clips.findIndex(clip => clip.id === selected.id); commit([...clips.slice(0, index + 1), copy, ...clips.slice(index + 1)]); setSelectedId(copy.id); }
  function split() { if (!selected || selected.duration < .4) return; const half = selected.duration / 2; const right = { ...selected, id: `${selected.id}-${Date.now()}`, title: `${selected.title} B`, sourceStart: selected.sourceStart + half, duration: half }; const index = clips.findIndex(clip => clip.id === selected.id); commit([...clips.slice(0, index), { ...selected, title: `${selected.title} A`, duration: half }, right, ...clips.slice(index + 1)]); setSelectedId(right.id); }
  function toggleTrackMute(track: Track) {
    const muted = !track.muted;
    setTracks(value => value.map(item => item.id === track.id ? { ...item, muted } : item));
    playingAudioRef.current.filter(item => item.trackId === track.id).forEach(({ audio }) => { audio.muted = muted; });
    if (previewRef.current && tracks.find(item => item.id === selected?.trackId)?.id === track.id) previewRef.current.muted = muted || !settings.keepOriginalAudio;
  }
  function setTrackVolume(track: Track, volume: number) {
    setTracks(value => value.map(item => item.id === track.id ? { ...item, volume } : item));
    playingAudioRef.current.filter(item => item.trackId === track.id).forEach(item => { item.baseVolume = Math.min(1, volume); item.audio.volume = item.baseVolume; });
    if (previewRef.current && track.type === "video") { videoBaseVolumeRef.current = Math.min(1, settings.originalAudioVolume * volume); previewRef.current.volume = videoBaseVolumeRef.current; }
  }
  function addTrack(type: Track["type"]) { const count = tracks.filter(track => track.type === type).length + 1; const label = type === "audio" ? "音频" : type === "overlay" ? "叠加" : "视频"; setTracks(value => [...value, { id: `${type}-${Date.now()}`, name: `${label} ${count}`, type, muted: false, locked: false }]); }
  async function uploadMedia(file?: File) {
    if (!file) return; stop(); setUploading(true);
    try {
      const target = tracks.find(track => track.id === uploadTrackId);
      const isAudio = file.type.startsWith("audio/") || target?.type === "audio";
      const url = isAudio ? await uploadAudio(file) : await uploadVideo(file);
      let track = target || tracks.find(item => item.type === (isAudio ? "audio" : "video"));
      if (!track) { track = { id: `${isAudio ? "audio" : "video"}-${Date.now()}`, name: isAudio ? "音频 1" : "视频 1", type: isAudio ? "audio" : "video", muted: false, locked: false }; setTracks(value => [...value, track!]); }
      const duration = await readMediaDuration(url, isAudio ? "audio" : "video");
      const clip: Clip = { id: `upload-${Date.now()}`, itemId: 0, title: file.name, url, sourceStart: 0, duration, trackId: track.id };
      commit([...clips, clip]); setSelectedId(clip.id); toast.success("素材已添加到时间线");
    } catch { toast.error("素材上传失败"); } finally { setUploading(false); if (mediaInputRef.current) mediaInputRef.current.value = ""; }
  }
  async function uploadBgm(file?: File) { if (!file) return; setUploading(true); try { const url = await uploadAudio(file); setSettings(value => ({ ...value, bgmUrl: url })); toast.success("背景音乐已更新"); } catch { toast.error("音频上传失败"); } finally { setUploading(false); } }
  function addProjectClip(source: Clip, trackId?: string) {
    const track = tracks.find(item => item.id === trackId) || tracks.find(item => item.type === "video") || baseTrack;
    const copy = { ...source, id: `${source.id}-copy-${Date.now()}`, trackId: track.id };
    commit([...clips, copy]); setSelectedId(copy.id); toast.success(`已添加到${track.name}`);
  }
  function addAllProjectClips() {
    const track = tracks.find(item => item.id === targetTrackId) || tracks.find(item => item.type === "video") || baseTrack;
    if (!filteredClips.length) return;
    const timestamp = Date.now();
    const copies = filteredClips.map((source, index) => ({ ...source, id: `${source.id}-batch-${timestamp}-${index}`, trackId: track.id }));
    commit([...clips, ...copies]); setSelectedId(copies[0].id); toast.success(`已将 ${copies.length} 个素材加入${track.name}`);
  }
  function removeTrack(track: Track) {
    if (track.id === "video-1") { toast.error("主视频轨不能删除"); return; }
    const trackClips = clips.filter(clip => clip.trackId === track.id);
    if (trackClips.length && !window.confirm(`删除“${track.name}”将同时移除其中 ${trackClips.length} 个片段，是否继续？`)) return;
    commit(clips.filter(clip => clip.trackId !== track.id));
    setTracks(value => value.filter(item => item.id !== track.id));
    if (selected && selected.trackId === track.id) setSelectedId(null);
    if (targetTrackId === track.id) setTargetTrackId("video-1");
    toast.success(`已删除${track.name}`);
  }
  async function exportVideo() {
    setExporting(true); save();
    try {
      const trackOffsets = new Map<string, number>();
      const exportClips = clips.map(({ itemId, sourceStart, duration, url, trackId }) => {
        const track = tracks.find(item => item.id === trackId) || baseTrack;
        const timelineStart = trackOffsets.get(trackId) || 0;
        trackOffsets.set(trackId, timelineStart + duration);
        return { itemId, sourceStart, duration, trackId, trackType: track.type, trackMuted: track.muted, trackVolume: track.volume ?? 1, timelineStart, ...(itemId <= 0 ? { sourceUrl: url } : {}) };
      });
      await storyboardApi.composeEpisodeVideo(episodeId, { generateSubtitleFiles: true, ...settings, clips: exportClips });
      toast.success("导出任务已提交"); router.push(`/projects/${params.id}/storyboards`);
    } catch { toast.error("导出任务提交失败"); } finally { setExporting(false); }
  }

  if (loading) return <div className="flex h-[80vh] items-center justify-center bg-[#111315]"><Loader2 className="size-6 animate-spin text-white" /></div>;
  if (!episodeId) return <div className="p-8 text-sm text-muted-foreground">缺少有效的 episodeId。</div>;

  return <div className="flex h-full min-h-[calc(100dvh-7rem)] flex-col overflow-hidden bg-[#111315] text-zinc-200 lg:min-h-0">
    <header className="flex h-12 shrink-0 items-center border-b border-white/10 bg-[#191b1f] px-1.5 sm:px-2">
      <Button variant="ghost" size="icon-sm" className="text-zinc-300 hover:bg-white/10 hover:text-white" title="返回分镜" onClick={() => router.push(`/projects/${params.id}/storyboards`)}><ArrowLeft /></Button>
      <div className="mx-2 h-5 w-px bg-white/10" />
      <Button variant="ghost" size="icon-sm" className="text-zinc-400 hover:bg-white/10 hover:text-white" title="主菜单"><Menu /></Button>
      <div className="ml-2 min-w-0"><div className="flex items-center gap-2"><span className="truncate text-xs font-medium text-white">{episode?.title || `第 ${episode?.episodeNumber || "-"} 集`}</span><span className="rounded bg-white/8 px-1.5 py-0.5 text-[10px] text-zinc-500">25 FPS</span></div></div>
      <div className="ml-auto flex items-center gap-1"><span className="mr-2 hidden text-[11px] text-zinc-500 xl:inline">{savedAt ? `已保存 ${new Date(savedAt).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}` : "未保存"}</span><Button variant="ghost" size="sm" className="text-zinc-300 hover:bg-white/10 hover:text-white" onClick={save}><Save />保存</Button><Button size="sm" className="ml-1 bg-sky-500 text-white hover:bg-sky-400" disabled={exporting || !clips.length} onClick={exportVideo}>{exporting ? <Loader2 className="animate-spin" /> : <Upload />}导出<ChevronDown /></Button></div>
    </header>

    <main className="flex min-h-0 flex-1 flex-col overflow-y-auto lg:grid lg:grid-cols-[240px_minmax(420px,1fr)_272px] lg:overflow-hidden">
      <aside className="order-2 flex max-h-[22rem] min-h-[16rem] shrink-0 flex-col border-y border-white/10 bg-[#17191c] lg:order-none lg:max-h-none lg:min-h-0 lg:border-y-0 lg:border-r">
        <div className="grid h-11 shrink-0 grid-cols-3 border-b border-white/10 px-2">
          {[{ id: "media", icon: Film, label: "媒体" }, { id: "text", icon: Captions, label: "文字" }, { id: "audio", icon: Music2, label: "音频" }].map(item => <button key={item.id} onClick={() => setMediaTab(item.id as typeof mediaTab)} className={`flex items-center justify-center gap-1.5 border-b-2 text-xs ${mediaTab === item.id ? "border-sky-400 text-white" : "border-transparent text-zinc-500 hover:text-zinc-300"}`}><item.icon className="size-3.5" />{item.label}</button>)}
        </div>
        <div className="p-3"><div className="relative"><Search className="absolute left-2.5 top-2.5 size-3.5 text-zinc-600" /><Input value={search} onChange={event => setSearch(event.target.value)} className="h-8 border-white/10 bg-black/20 pl-8 text-xs text-white placeholder:text-zinc-600" placeholder="搜索素材" /></div></div>
        <div className="flex items-center justify-between px-3 pb-2"><span className="text-[11px] font-medium text-zinc-400">项目素材 · {filteredClips.length}</span><Button size="icon-xs" variant="ghost" className="text-zinc-400 hover:bg-white/10 hover:text-white" title="导入素材" disabled={uploading} onClick={() => { setUploadTrackId(undefined); mediaInputRef.current?.click(); }}>{uploading ? <Loader2 className="animate-spin" /> : <Plus />}</Button></div>
        <div className="mx-3 mb-3 flex gap-1.5"><select aria-label="素材目标轨道" value={targetTrackId} onChange={event => setTargetTrackId(event.target.value)} style={{ colorScheme: "dark" }} className="h-7 min-w-0 flex-1 rounded border border-white/10 bg-[#111315] px-2 text-[10px] text-white">{tracks.filter(track => track.type !== "audio").map(track => <option className="bg-[#191b1f] text-white" key={track.id} value={track.id}>{track.name}</option>)}</select><Button size="sm" className="h-7 bg-sky-500 px-2 text-[10px] text-white hover:bg-sky-400" disabled={!filteredClips.length} onClick={addAllProjectClips}>一键加入</Button></div>
        <input ref={mediaInputRef} hidden type="file" accept="video/*,audio/*" onChange={event => void uploadMedia(event.target.files?.[0])} />
        <div className="grid min-h-0 flex-1 auto-rows-min grid-cols-2 gap-2 overflow-y-auto px-3 pb-3">
          {filteredClips.map(clip => <div key={clip.id} className={`group overflow-hidden rounded border ${selectedId === clip.id ? "border-sky-400" : "border-white/10 hover:border-white/25"}`}><button onClick={() => selectClip(clip.id)} className="block w-full text-left"><div className="relative aspect-video bg-black"><video src={resolveMediaUrl(clip.url) || undefined} muted preload="metadata" className="h-full w-full object-cover opacity-80" /><span className="absolute bottom-1 right-1 rounded bg-black/75 px-1 text-[9px] text-white">{formatTime(clip.duration)}</span></div><div className="truncate px-1.5 py-1 text-[10px] text-zinc-400 group-hover:text-white">{clip.title}</div></button><button className="block w-full border-t border-white/10 py-1 text-[9px] text-sky-300 hover:bg-sky-500/15" onClick={() => addProjectClip(clip, targetTrackId)}>加入{tracks.find(track => track.id === targetTrackId)?.name || "主视频"}</button></div>)}
          {!filteredClips.length && <div className="col-span-2 py-10 text-center text-xs text-zinc-600">暂无匹配素材</div>}
        </div>
        {activeTemplate && <div className="border-t border-white/10 p-3"><div className="flex items-center gap-2 text-[11px] text-zinc-400"><Sparkles className="size-3.5 text-amber-400" /><span className="truncate">模板：{activeTemplate.name}</span></div></div>}
      </aside>

      <section className="order-1 flex min-h-[18rem] shrink-0 flex-col bg-[#0d0f11] lg:order-none lg:min-h-0 lg:shrink">
        <div className="flex h-9 shrink-0 items-center justify-between border-b border-white/8 px-3"><span className="text-[11px] text-zinc-500">节目监视器</span><div className="flex gap-1"><Button size="icon-xs" variant="ghost" className="text-zinc-500 hover:bg-white/10 hover:text-white" title="适应画布"><ImageIcon /></Button><Button size="icon-xs" variant="ghost" className="text-zinc-500 hover:bg-white/10 hover:text-white" title="显示设置"><Settings2 /></Button></div></div>
        <div className="flex min-h-0 flex-1 items-center justify-center p-5"><div className="relative aspect-video max-h-full w-full max-w-4xl bg-black shadow-[0_18px_60px_rgba(0,0,0,.5)]"><video ref={previewRef} src={selected && selectedTrack?.type !== "audio" ? resolveMediaUrl(selected.url) || undefined : undefined} className="h-full w-full object-contain" />{!selected && <div className="absolute inset-0 flex flex-col items-center justify-center text-zinc-700"><Clapperboard className="mb-3 size-9" /><span className="text-xs">将素材添加到时间线开始剪辑</span></div>}{selected && selectedTrack?.type === "audio" && <div className="absolute inset-0 flex flex-col items-center justify-center text-zinc-600"><Music2 className="mb-3 size-9 text-emerald-500" /><span className="max-w-xs truncate text-xs">{selected.title}</span><span className="mt-1 text-[10px]">音频片段已选中，按播放键试听</span></div>}{selected?.stickers?.map((sticker, index) => <span key={`${sticker}-${index}`} className="absolute left-1/2 top-1/2 -translate-x-1/2 bg-black/55 px-3 py-1 text-lg font-semibold text-white">{sticker}</span>)}</div></div>
        <div className="flex h-12 shrink-0 items-center justify-center gap-3 border-t border-white/8 bg-[#15171a]"><span className="w-20 text-right font-mono text-xs text-sky-400">{formatTime(playhead)}</span><Button size="icon-sm" variant="ghost" className="text-zinc-300 hover:bg-white/10 hover:text-white" title="播放/暂停 (空格)" onClick={play}>{playing ? <Pause /> : <Play className="fill-current" />}</Button><span className="w-20 font-mono text-xs text-zinc-600">{formatTime(total)}</span></div>
      </section>

      <aside className="order-3 flex max-h-[26rem] min-h-[18rem] shrink-0 flex-col border-t border-white/10 bg-[#17191c] lg:order-none lg:max-h-none lg:min-h-0 lg:border-l lg:border-t-0">
        <div className="flex h-11 shrink-0 border-b border-white/10 px-2">{([{ id: "clip", label: "画面" }, { id: "audio", label: "音频" }, { id: "export", label: "成片" }] as const).map(tab => <button key={tab.id} onClick={() => setInspectorTab(tab.id)} className={`flex-1 border-b-2 text-xs ${inspectorTab === tab.id ? "border-sky-400 text-white" : "border-transparent text-zinc-500"}`}>{tab.label}</button>)}</div>
        <div className="min-h-0 flex-1 overflow-y-auto p-4">
          {inspectorTab === "clip" && (selected ? <div className="space-y-5"><InspectorTitle icon={SlidersHorizontal} title="基础" /><Field label="片段名称"><Input value={selected.title} onChange={event => patchSelected({ title: event.target.value })} className="editor-input" /></Field><div className="grid grid-cols-2 gap-2"><Field label="入点"><Input type="number" min="0" step=".1" value={selected.sourceStart} onChange={event => patchSelected({ sourceStart: Math.max(0, +event.target.value) })} className="editor-input" /></Field><Field label="时长"><Input type="number" min=".1" step=".1" value={selected.duration} onChange={event => patchSelected({ duration: Math.max(.1, +event.target.value) })} className="editor-input" /></Field></div><Field label={`不透明度 ${Math.round((selected.opacity ?? 1) * 100)}%`}><input className="w-full accent-sky-400" type="range" min="0" max="1" step=".05" value={selected.opacity ?? 1} onChange={event => patchSelected({ opacity: +event.target.value })} /></Field><Field label="滤镜"><select value={selected.filter || "none"} onChange={event => patchSelected({ filter: event.target.value })} className="h-8 w-full rounded border border-white/10 bg-black/20 px-2 text-xs"><option value="none">无</option><option value="cinematic">电影感</option><option value="warm">暖色回忆</option><option value="cold">冷色</option><option value="mono">黑白</option><option value="vintage">复古胶片</option></select></Field><div className="grid grid-cols-2 gap-2"><Button variant="outline" size="sm" className="border-white/10 bg-transparent hover:bg-white/10 hover:text-white" onClick={split}><Scissors />分割</Button><Button variant="outline" size="sm" className="border-white/10 bg-transparent hover:bg-white/10 hover:text-white" onClick={duplicate}><Copy />复制</Button></div><Button variant="ghost" size="sm" className="w-full text-red-400 hover:bg-red-500/10 hover:text-red-300" onClick={removeSelected}><Trash2 />删除片段</Button></div> : <div className="py-16 text-center text-xs text-zinc-600">选择时间线片段以调整参数</div>)}
          {inspectorTab === "audio" && <div className="space-y-5"><InspectorTitle icon={Volume2} title="混音" /><Toggle label="保留视频原声" checked={settings.keepOriginalAudio} onChange={checked => setSettings(value => ({ ...value, keepOriginalAudio: checked }))} /><Field label={`原声音量 ${Math.round(settings.originalAudioVolume * 100)}%`}><input className="w-full accent-sky-400" type="range" min="0" max="2" step=".05" value={settings.originalAudioVolume} onChange={event => setSettings(value => ({ ...value, originalAudioVolume: +event.target.value }))} /></Field><div className="border-t border-white/10 pt-4"><div className="mb-3 flex items-center gap-2 text-xs font-medium"><Music2 className="size-4 text-emerald-400" />背景音乐</div><input ref={audioInputRef} hidden type="file" accept="audio/*" onChange={event => void uploadBgm(event.target.files?.[0])} /><Button variant="outline" size="sm" className="w-full border-white/10 bg-transparent hover:bg-white/10 hover:text-white" onClick={() => audioInputRef.current?.click()}><Upload />{settings.bgmUrl ? "替换音乐" : "上传音乐"}</Button></div><Field label={`音乐音量 ${Math.round(settings.bgmVolume * 100)}%`}><input disabled={!settings.bgmUrl} className="w-full accent-emerald-400" type="range" min="0" max="2" step=".05" value={settings.bgmVolume} onChange={event => setSettings(value => ({ ...value, bgmVolume: +event.target.value }))} /></Field></div>}
          {inspectorTab === "export" && <div className="space-y-4"><InspectorTitle icon={Settings2} title="成片设置" /><div className="rounded border border-sky-400/30 bg-sky-500/8 p-3"><div className="mb-3 flex items-center gap-2 text-xs font-medium text-white"><Film className="size-3.5 text-sky-400" />开场与收尾</div><div className="grid grid-cols-2 gap-2"><Field label="开头渐入（秒）"><Input type="number" min="0" max={Math.max(0, total / 2)} step=".1" value={settings.fadeInDuration} onChange={event => setSettings(value => ({ ...value, fadeInDuration: Math.max(0, Math.min(total / 2, +event.target.value)) }))} className="h-8 border-white/15 bg-black/25 text-white" /></Field><Field label="结束渐出（秒）"><Input type="number" min="0" max={Math.max(0, total / 2)} step=".1" value={settings.fadeOutDuration} onChange={event => setSettings(value => ({ ...value, fadeOutDuration: Math.max(0, Math.min(total / 2, +event.target.value)) }))} className="h-8 border-white/15 bg-black/25 text-white" /></Field></div><div className="mt-3 grid grid-cols-3 gap-1">{[0, .5, 1].map(duration => <button key={duration} type="button" onClick={() => setSettings(value => ({ ...value, fadeInDuration: duration, fadeOutDuration: duration }))} className={`h-6 rounded border text-[9px] ${settings.fadeInDuration === duration && settings.fadeOutDuration === duration ? "border-sky-400 bg-sky-500/25 text-white" : "border-white/10 text-zinc-500 hover:text-white"}`}>{duration === 0 ? "关闭" : `${duration} 秒`}</button>)}</div><p className="mt-2 text-[10px] text-zinc-500">画面和声音同步生效</p></div><Toggle label="烧录字幕" checked={settings.burnSubtitles} onChange={checked => setSettings(value => ({ ...value, burnSubtitles: checked }))} /><div className="space-y-2 rounded border border-white/10 bg-black/15 p-3 text-[11px] text-zinc-500"><div className="flex justify-between"><span>时长</span><span className="text-zinc-300">{formatTime(total)}</span></div><div className="flex justify-between"><span>帧率</span><span className="text-zinc-300">25 FPS</span></div><div className="flex justify-between"><span>片段</span><span className="text-zinc-300">{clips.length}</span></div></div></div>}
        </div>
      </aside>
    </main>

    <section className="flex h-[220px] shrink-0 flex-col border-t border-white/10 bg-[#141619] sm:h-[250px]">
      <div className="flex h-10 shrink-0 items-center border-b border-white/10 px-2"><Button size="icon-xs" variant="ghost" title="撤销" disabled={!undoStack.length} onClick={undo} className="text-zinc-400 hover:bg-white/10 hover:text-white"><Undo2 /></Button><Button size="icon-xs" variant="ghost" title="重做" disabled={!redoStack.length} onClick={redo} className="text-zinc-400 hover:bg-white/10 hover:text-white"><Redo2 /></Button><div className="mx-2 h-5 w-px bg-white/10" /><Button size="icon-xs" variant="ghost" title="在播放头分割" disabled={!selected} onClick={split} className="text-zinc-400 hover:bg-white/10 hover:text-white"><Scissors /></Button><Button size="icon-xs" variant="ghost" title="删除" disabled={!selected} onClick={removeSelected} className="text-zinc-400 hover:bg-white/10 hover:text-white"><Trash2 /></Button><div className="mx-2 h-5 w-px bg-white/10" /><Button size="sm" variant="ghost" className="text-xs text-zinc-400 hover:bg-white/10 hover:text-white" onClick={() => addTrack("video")}><Plus />视频轨</Button><Button size="sm" variant="ghost" className="text-xs text-zinc-400 hover:bg-white/10 hover:text-white" onClick={() => addTrack("overlay")}><Layers3 />叠加轨</Button><Button size="sm" variant="ghost" className="text-xs text-zinc-400 hover:bg-white/10 hover:text-white" onClick={() => addTrack("audio")}><Music2 />音频轨</Button><div className="ml-auto flex items-center"><ZoomOut className="size-3.5 text-zinc-600" /><input className="mx-2 w-24 accent-sky-400" type="range" min="24" max="80" value={scale} onChange={event => setScale(+event.target.value)} /><ZoomIn className="size-3.5 text-zinc-600" /><Button size="icon-xs" variant="ghost" title="重置草稿" className="ml-2 text-zinc-500 hover:bg-white/10 hover:text-white" onClick={() => { localStorage.removeItem(storageKey); location.reload(); }}><RotateCcw /></Button></div></div>
      <div ref={timelineScrollRef} className="min-h-0 flex-1 overflow-auto"><div className="relative min-h-full" style={{ width: timelineWidth + 144 }}><div className="sticky left-0 z-30 flex h-6 w-36 items-center border-r border-white/10 bg-[#191b1f] px-3 text-[9px] text-zinc-600">轨道</div><div className="absolute left-36 top-0 h-6 cursor-crosshair border-b border-white/10" style={{ width: timelineWidth }} onPointerDown={event => { stop(); setPlayhead(Math.min(total, Math.max(0, (event.clientX - event.currentTarget.getBoundingClientRect().left) / scale))); }}>{Array.from({ length: Math.ceil(timelineWidth / scale) + 1 }, (_, index) => <span key={index} className="absolute top-0 h-2 border-l border-white/20 pl-1 text-[9px] text-zinc-600" style={{ left: index * scale }}>{index % 5 === 0 ? `${index}s` : ""}</span>)}</div><button type="button" aria-label="拖动播放头" title="拖动播放头" onPointerDown={beginScrub} className="absolute bottom-0 top-0 z-40 w-3 -translate-x-1/2 cursor-ew-resize touch-none" style={{ left: 144 + playhead * scale }}><span className="absolute bottom-0 left-1/2 top-0 w-px bg-sky-400" /><span className="absolute left-0 top-0 h-0 w-0 border-x-[6px] border-t-[7px] border-x-transparent border-t-sky-400" /></button>
        <div className="pt-0">{tracks.map(track => { let offset = 0; return <div key={track.id} className="relative flex h-[68px] border-b border-white/8"><div className="sticky left-0 z-30 flex w-36 shrink-0 flex-col justify-center border-r border-white/10 bg-[#191b1f] px-2"><div className="flex items-center gap-0.5"><TrackIcon type={track.type} /><span className="min-w-0 flex-1 truncate text-[11px] text-zinc-400">{track.name}</span><button title={track.muted ? "取消静音" : "静音"} onClick={() => toggleTrackMute(track)} className="p-1 text-zinc-600 hover:text-white">{track.muted ? <VolumeX className="size-3" /> : <Volume2 className="size-3" />}</button><button title={track.locked ? "解锁" : "锁定"} onClick={() => setTracks(value => value.map(item => item.id === track.id ? { ...item, locked: !item.locked } : item))} className="p-1 text-zinc-600 hover:text-white">{track.locked ? <Lock className="size-3" /> : <LockOpen className="size-3" />}</button><button title="添加素材" onClick={() => { setUploadTrackId(track.id); mediaInputRef.current?.click(); }} className="p-1 text-zinc-600 hover:text-white"><Plus className="size-3" /></button>{track.id !== "video-1" && <button title="删除轨道" onClick={() => removeTrack(track)} className="p-1 text-zinc-600 hover:text-red-400"><Trash2 className="size-3" /></button>}</div><div className="mt-1 flex items-center gap-1"><Volume2 className="size-2.5 text-zinc-600" /><input aria-label={`${track.name}音量`} title={`轨道音量 ${Math.round((track.volume ?? 1) * 100)}%`} className="h-2 min-w-0 flex-1 accent-sky-400" type="range" min="0" max="1" step=".01" value={track.volume ?? 1} onChange={event => setTrackVolume(track, +event.target.value)} /><span className="w-6 text-right text-[8px] text-zinc-600">{Math.round((track.volume ?? 1) * 100)}</span></div></div><div className="relative h-full" style={{ width: timelineWidth }}>{clips.filter(clip => clip.trackId === track.id).map(clip => { const left = offset; const width = Math.max(36, clip.duration * scale - 2); offset += clip.duration * scale; return <div key={clip.id} className={`group absolute top-2 h-[52px] overflow-hidden rounded border ${clipColors[track.type]} ${selectedId === clip.id ? "ring-1 ring-white" : "hover:brightness-125"} ${track.muted ? "opacity-45" : ""}`} style={{ left, width }}><button type="button" onClick={() => selectClip(clip.id)} disabled={track.locked} className="absolute inset-0 w-full text-left"><span className="absolute inset-0 opacity-25">{track.type === "video" && <video src={resolveMediaUrl(clip.url) || undefined} muted className="h-full w-full object-cover" />}</span><span className="relative block truncate px-2 pt-1 pr-6 text-[10px] text-white">{clip.title}</span><span className="relative px-2 text-[9px] text-white/55">{clip.duration.toFixed(1)}s</span></button>{!track.locked && <button type="button" title="移除片段" aria-label={`移除 ${clip.title}`} onClick={() => removeClip(clip.id)} className="absolute right-0.5 top-0.5 z-10 rounded bg-black/55 p-0.5 text-white/60 opacity-0 hover:text-red-300 group-hover:opacity-100"><Trash2 className="size-3" /></button>}</div>})}</div></div>})}</div></div></div>
    </section>
  </div>;
}

function InspectorTitle({ icon: Icon, title }: { icon: typeof Settings2; title: string }) { return <div className="flex items-center gap-2 border-b border-white/10 pb-2 text-xs font-medium text-zinc-300"><Icon className="size-3.5 text-sky-400" />{title}</div>; }
function Field({ label, children }: { label: string; children: React.ReactNode }) { return <label className="block text-[11px] text-zinc-500"><span className="mb-1.5 block">{label}</span>{children}</label>; }
function Toggle({ label, checked, onChange }: { label: string; checked: boolean; onChange: (checked: boolean) => void }) { return <label className="flex items-center justify-between text-xs text-zinc-300"><span>{label}</span><button type="button" role="switch" aria-checked={checked} onClick={() => onChange(!checked)} className={`relative h-5 w-9 rounded-full transition-colors ${checked ? "bg-sky-500" : "bg-zinc-700"}`}><span className={`absolute top-0.5 size-4 rounded-full bg-white transition-transform ${checked ? "left-[18px]" : "left-0.5"}`} /></button></label>; }
function TrackIcon({ type }: { type: Track["type"] }) { return type === "audio" ? <Mic2 className="size-3.5 text-emerald-400" /> : type === "overlay" ? <Layers3 className="size-3.5 text-fuchsia-400" /> : <Film className="size-3.5 text-sky-400" />; }
function toClip(item: StoryboardItem, index: number): Clip { return { id: `item-${item.id}`, itemId: item.id, title: item.shotNumber || item.autoShotNumber || `镜头 ${index + 1}`, url: item.videoUrl || item.generatedVideoUrl || "", sourceStart: 0, duration: Math.max(.1, item.duration || 5), trackId: "video-1", filter: "none", stickers: [], keyframes: [] }; }
