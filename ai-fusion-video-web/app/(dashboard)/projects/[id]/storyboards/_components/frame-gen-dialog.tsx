"use client";

import { useMemo, useState } from "react";
import { Check, Images, X } from "lucide-react";
import { cn } from "@/lib/utils";
import type { StoryboardItem } from "@/lib/api/storyboard";

export function getStoryboardFrames(item: StoryboardItem) {
  try {
    const data = item.customData ? JSON.parse(item.customData) : {};
    return { firstFrameImageUrl: data.firstFrameImageUrl as string | undefined, lastFrameImageUrl: data.lastFrameImageUrl as string | undefined };
  } catch { return {}; }
}

interface Props { open: boolean; items: StoryboardItem[]; onClose: () => void; onConfirm: (ids: number[], overwrite: boolean) => void; }

export function FrameGenDialog({ open, items, onClose, onConfirm }: Props) {
  const defaults = useMemo(() => new Set(items.filter((item) => { const f = getStoryboardFrames(item); return !f.firstFrameImageUrl || !f.lastFrameImageUrl; }).map((item) => item.id)), [items]);
  const [selected, setSelected] = useState(defaults);
  const [overwrite, setOverwrite] = useState(false);
  if (!open) return null;
  const toggle = (id: number) => setSelected((current) => {
    const next = new Set(current);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    return next;
  });
  return <div className="fixed inset-0 z-50 flex items-center justify-center">
    <div className="absolute inset-0 bg-black/50 backdrop-blur-sm" onClick={onClose} />
    <div className="relative flex max-h-[80vh] w-[520px] max-w-[90vw] flex-col overflow-hidden rounded-lg border border-border/30 bg-card shadow-2xl">
      <div className="flex items-center justify-between border-b border-border/20 px-5 py-4"><div className="flex items-center gap-2"><Images className="h-4 w-4 text-emerald-500" /><h3 className="text-sm font-semibold">批量生成首尾帧</h3></div><button onClick={onClose} title="关闭"><X className="h-4 w-4" /></button></div>
      <label className="flex items-center justify-between border-b border-border/10 px-5 py-3 text-xs"><span>覆盖已有完整首尾帧</span><input type="checkbox" checked={overwrite} onChange={(e) => setOverwrite(e.target.checked)} /></label>
      <div className="flex-1 space-y-1.5 overflow-y-auto p-3">{items.map((item) => { const f = getStoryboardFrames(item); const complete = !!f.firstFrameImageUrl && !!f.lastFrameImageUrl; return <button key={item.id} onClick={() => toggle(item.id)} className={cn("flex w-full items-center gap-3 rounded-lg px-3 py-2.5 text-left", selected.has(item.id) ? "bg-primary/8 ring-1 ring-primary/20" : "hover:bg-muted/30")}><span className={cn("flex h-5 w-5 items-center justify-center rounded border", selected.has(item.id) && "border-primary bg-primary text-primary-foreground")}>{selected.has(item.id) && <Check className="h-3 w-3" />}</span><span className="min-w-0 flex-1"><span className="block text-xs font-medium">#{item.shotNumber || item.autoShotNumber || item.id}</span><span className="block truncate text-[10px] text-muted-foreground">{item.content || item.sceneExpectation || "无画面描述"}</span></span><span className={cn("text-[10px]", complete ? "text-emerald-500" : "text-amber-500")}>{complete ? "首尾帧完整" : "首尾帧未完整"}</span></button>; })}</div>
      <div className="flex justify-end gap-2 border-t border-border/20 px-5 py-3.5"><button onClick={onClose} className="px-4 py-2 text-xs">取消</button><button disabled={!selected.size} onClick={() => { onConfirm([...selected], overwrite); onClose(); }} className="rounded-lg bg-emerald-600 px-5 py-2 text-xs font-medium text-white disabled:opacity-40">生成首尾帧 ({selected.size})</button></div>
    </div>
  </div>;
}
