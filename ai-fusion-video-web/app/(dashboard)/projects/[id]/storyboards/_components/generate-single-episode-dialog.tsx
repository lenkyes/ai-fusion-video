"use client";

import { useEffect, useState } from "react";
import { Loader2, RefreshCw } from "lucide-react";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { scriptApi, type ScriptEpisode } from "@/lib/api/script";
import { storyboardApi, type StoryboardEpisode } from "@/lib/api/storyboard";
import { cn } from "@/lib/utils";

export interface SingleEpisodeGenerationTarget {
  scriptEpisode: ScriptEpisode;
  existingStoryboardEpisodeId?: number;
}

interface EpisodeOption extends SingleEpisodeGenerationTarget {
  state: "missing" | "empty" | "complete";
}

export function GenerateSingleEpisodeDialog({
  open,
  scriptId,
  storyboardId,
  onClose,
  onConfirm,
}: {
  open: boolean;
  scriptId: number;
  storyboardId: number;
  onClose: () => void;
  onConfirm: (target: SingleEpisodeGenerationTarget) => Promise<void>;
}) {
  const [options, setOptions] = useState<EpisodeOption[]>([]);
  const [loading, setLoading] = useState(false);
  const [submittingId, setSubmittingId] = useState<number | null>(null);
  const [error, setError] = useState("");

  useEffect(() => {
    if (!open) return;
    setLoading(true);
    setError("");
    Promise.all([
      scriptApi.listEpisodes(scriptId),
      storyboardApi.listEpisodes(storyboardId),
    ])
      .then(async ([scriptEpisodes, storyboardEpisodes]) => {
        const sceneCounts = new Map<number, number>();
        await Promise.all(
          storyboardEpisodes.map(async (episode) => {
            const scenes = await storyboardApi.listScenesByEpisode(episode.id);
            sceneCounts.set(episode.id, scenes.length);
          })
        );
        const byNumber = new Map<number, StoryboardEpisode>();
        storyboardEpisodes.forEach((episode) => {
          if (episode.episodeNumber != null) byNumber.set(episode.episodeNumber, episode);
        });
        setOptions(scriptEpisodes.map((scriptEpisode) => {
          const existing = byNumber.get(scriptEpisode.episodeNumber);
          if (!existing) return { scriptEpisode, state: "missing" as const };
          if ((sceneCounts.get(existing.id) ?? 0) === 0) {
            return { scriptEpisode, state: "empty" as const, existingStoryboardEpisodeId: existing.id };
          }
          return { scriptEpisode, state: "complete" as const, existingStoryboardEpisodeId: existing.id };
        }));
      })
      .catch((err) => setError(err instanceof Error ? err.message : "加载分集失败"))
      .finally(() => setLoading(false));
  }, [open, scriptId, storyboardId]);

  const handleGenerate = async (option: EpisodeOption) => {
    setSubmittingId(option.scriptEpisode.id);
    setError("");
    try {
      await onConfirm(option);
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : "启动单集生成失败");
    } finally {
      setSubmittingId(null);
    }
  };

  return (
    <Dialog open={open} onOpenChange={(next) => !next && submittingId == null && onClose()}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>补生成单集分镜</DialogTitle>
          <DialogDescription>按剧本集数补生成缺失或未产生场次的分镜集。</DialogDescription>
        </DialogHeader>
        <div className="max-h-[55vh] space-y-1 overflow-y-auto py-2">
          {loading ? (
            <div className="flex items-center justify-center py-10 text-sm text-muted-foreground">
              <Loader2 className="mr-2 h-4 w-4 animate-spin" />加载分集状态
            </div>
          ) : options.map((option) => {
            const disabled = submittingId != null;
            return (
              <div key={option.scriptEpisode.id} className="flex items-center gap-3 border-b border-border/20 px-1 py-3 last:border-0">
                <div className="min-w-0 flex-1">
                  <div className="truncate text-sm font-medium">第 {option.scriptEpisode.episodeNumber} 集 · {option.scriptEpisode.title}</div>
                  <div className={cn("mt-0.5 text-xs", option.state === "complete" ? "text-muted-foreground" : "text-amber-500")}>
                    {option.state === "missing" ? "未生成分镜" : option.state === "empty" ? "分镜集为空，可重新生成" : "已有分镜，将覆盖重新生成"}
                  </div>
                </div>
                <button
                  type="button"
                  disabled={disabled}
                  onClick={() => handleGenerate(option)}
                  className="inline-flex h-8 items-center gap-1.5 rounded-md border border-border/40 px-3 text-xs font-medium hover:bg-muted disabled:cursor-not-allowed disabled:opacity-40"
                >
                  {submittingId === option.scriptEpisode.id ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <RefreshCw className="h-3.5 w-3.5" />}
                  {option.state === "missing" ? "生成" : "重新生成"}
                </button>
              </div>
            );
          })}
          {!loading && options.length === 0 && <div className="py-10 text-center text-sm text-muted-foreground">剧本暂无分集</div>}
        </div>
        {error && <p className="text-sm text-destructive">{error}</p>}
      </DialogContent>
    </Dialog>
  );
}
