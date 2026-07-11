"use client";

import { useEffect, useState, type FormEvent } from "react";
import {
  AlertCircle,
  AlertTriangle,
  Loader2,
  Sparkles,
  Timer,
  WandSparkles,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { cn } from "@/lib/utils";
import { buildTemplateGenerationPrompt, getVideoTemplate, VIDEO_TEMPLATES } from "@/lib/video-templates";

export type StoryboardGenerationMode = "regular" | "custom";

export interface StoryboardGenerationOptions {
  storyboardMode: StoryboardGenerationMode;
  shotDuration: number;
  templateId?: string;
  templatePrompt?: string;
}

interface GenerateStoryboardDialogProps {
  open: boolean;
  intent?: "create" | "reparse";
  onClose: () => void;
  onConfirm: (options: StoryboardGenerationOptions) => Promise<void> | void;
}

const DEFAULT_SHOT_DURATION = 15;
const MAX_SHOT_DURATION = 60;

export function GenerateStoryboardDialog({
  open,
  intent = "create",
  onClose,
  onConfirm,
}: GenerateStoryboardDialogProps) {
  const [storyboardMode, setStoryboardMode] =
    useState<StoryboardGenerationMode>("regular");
  const [shotDuration, setShotDuration] = useState(
    String(DEFAULT_SHOT_DURATION)
  );
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");
  const [templateId, setTemplateId] = useState("");
  const [storySeed, setStorySeed] = useState("");

  useEffect(() => {
    if (!open) return;
    setStoryboardMode("regular");
    setShotDuration(String(DEFAULT_SHOT_DURATION));
    setSubmitting(false);
    setError("");
    setTemplateId("");
    setStorySeed("");
  }, [open]);

  const handleClose = () => {
    if (submitting) return;
    onClose();
  };

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();

    let resolvedDuration = DEFAULT_SHOT_DURATION;
    if (storyboardMode === "custom") {
      const value = shotDuration.trim();
      const parsedDuration = Number(value);
      if (
        !/^\d+$/.test(value) ||
        !Number.isSafeInteger(parsedDuration) ||
        parsedDuration < 1 ||
        parsedDuration > MAX_SHOT_DURATION
      ) {
        setError(`请输入 1-${MAX_SHOT_DURATION} 之间的整数秒数`);
        return;
      }
      resolvedDuration = parsedDuration;
    }

    setSubmitting(true);
    setError("");
    try {
      const template = getVideoTemplate(templateId);
      await onConfirm({
        storyboardMode,
        shotDuration: resolvedDuration,
        templateId: template?.id,
        templatePrompt: template ? buildTemplateGenerationPrompt(template, storySeed) : undefined,
      });
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : "生成分镜失败，请重试");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Dialog
      open={open}
      onOpenChange={(nextOpen) => {
        if (!nextOpen) handleClose();
      }}
    >
      <DialogContent
        className="gap-0 overflow-hidden rounded-2xl border border-border/40 bg-card/95 p-0 shadow-2xl backdrop-blur-md sm:max-w-lg"
        showCloseButton={!submitting}
      >
        <form onSubmit={handleSubmit}>
          <DialogHeader className="border-b border-border/30 px-6 py-5 pr-14">
            <div className="flex items-center gap-2">
              <Sparkles className="h-5 w-5 text-cyan-400" />
              <DialogTitle className="text-lg font-semibold">
                {intent === "reparse" ? "重新生成分镜" : "生成分镜设置"}
              </DialogTitle>
            </div>
            <DialogDescription>
              {intent === "reparse"
                ? "确认后将删除当前分镜，并按所选时长策略重新生成。"
                : "选择 AI 拆分镜头时采用的时长策略。"}
            </DialogDescription>
          </DialogHeader>
          <div className="border-b border-border/30 px-6 py-4">
            <Label className="mb-2 block">创作模板</Label>
            <select className="h-10 w-full rounded-lg border border-border bg-background px-3 text-sm" value={templateId} onChange={event => setTemplateId(event.target.value)}>
              <option value="">不使用模板</option>
              {VIDEO_TEMPLATES.map(template => <option key={template.id} value={template.id}>{template.name}</option>)}
            </select>
            {getVideoTemplate(templateId) && <div className="mt-3 space-y-2"><p className="text-xs text-muted-foreground">{getVideoTemplate(templateId)?.description}</p><textarea className="min-h-20 w-full resize-y rounded-lg border border-border bg-background p-3 text-sm outline-none focus:border-primary" value={storySeed} onChange={event => setStorySeed(event.target.value)} placeholder="输入本次故事主题，例如：多年后回到外婆住过的老房子" required /></div>}
          </div>

          <div className="space-y-5 px-6 py-5">
            {intent === "reparse" && (
              <div className="flex gap-2.5 rounded-lg border border-amber-500/25 bg-amber-500/10 px-3.5 py-3 text-sm text-amber-700 dark:text-amber-300">
                <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
                <span>当前分镜的分集、场次和镜头数据将一并删除。</span>
              </div>
            )}

            <div className="space-y-2.5">
              <Label>分镜模式</Label>
              <div
                className="grid grid-cols-2 gap-2 rounded-lg bg-muted/35 p-1.5"
                role="radiogroup"
                aria-label="分镜模式"
              >
                <button
                  type="button"
                  role="radio"
                  aria-checked={storyboardMode === "regular"}
                  onClick={() => {
                    setStoryboardMode("regular");
                    setShotDuration(String(DEFAULT_SHOT_DURATION));
                    setError("");
                  }}
                  disabled={submitting}
                  className={cn(
                    "flex min-h-20 items-center gap-3 rounded-lg border px-3.5 py-3 text-left transition-colors",
                    "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/60",
                    storyboardMode === "regular"
                      ? "border-cyan-500/45 bg-background text-foreground shadow-sm"
                      : "border-transparent text-muted-foreground hover:bg-background/60 hover:text-foreground",
                    "disabled:cursor-not-allowed disabled:opacity-50"
                  )}
                >
                  <WandSparkles
                    className={cn(
                      "h-5 w-5 shrink-0",
                      storyboardMode === "regular"
                        ? "text-cyan-500"
                        : "text-muted-foreground"
                    )}
                  />
                  <span className="min-w-0">
                    <span className="block font-medium">常规</span>
                    <span className="mt-0.5 block text-xs text-muted-foreground">
                      按剧情节奏分配
                    </span>
                  </span>
                </button>

                <button
                  type="button"
                  role="radio"
                  aria-checked={storyboardMode === "custom"}
                  onClick={() => {
                    setStoryboardMode("custom");
                    setError("");
                  }}
                  disabled={submitting}
                  className={cn(
                    "flex min-h-20 items-center gap-3 rounded-lg border px-3.5 py-3 text-left transition-colors",
                    "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/60",
                    storyboardMode === "custom"
                      ? "border-amber-500/45 bg-background text-foreground shadow-sm"
                      : "border-transparent text-muted-foreground hover:bg-background/60 hover:text-foreground",
                    "disabled:cursor-not-allowed disabled:opacity-50"
                  )}
                >
                  <Timer
                    className={cn(
                      "h-5 w-5 shrink-0",
                      storyboardMode === "custom"
                        ? "text-amber-500"
                        : "text-muted-foreground"
                    )}
                  />
                  <span className="min-w-0">
                    <span className="block font-medium">自定义</span>
                    <span className="mt-0.5 block text-xs text-muted-foreground">
                      每个镜头统一时长
                    </span>
                  </span>
                </button>
              </div>
            </div>

            {storyboardMode === "custom" && (
              <div className="space-y-2.5">
                <Label htmlFor="storyboard-shot-duration">统一镜头时长</Label>
                <div className="relative max-w-44">
                  <Input
                    id="storyboard-shot-duration"
                    type="number"
                    inputMode="numeric"
                    min={1}
                    max={MAX_SHOT_DURATION}
                    step={1}
                    value={shotDuration}
                    onChange={(event) => {
                      setShotDuration(event.target.value);
                      setError("");
                    }}
                    disabled={submitting}
                    className="rounded-lg pr-11"
                    autoFocus
                  />
                  <span className="pointer-events-none absolute inset-y-0 right-3 flex items-center text-sm text-muted-foreground">
                    秒
                  </span>
                </div>
              </div>
            )}

            {error && (
              <div
                className="flex items-start gap-2 text-sm text-destructive"
                role="alert"
              >
                <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
                <span>{error}</span>
              </div>
            )}
          </div>

          <DialogFooter className="border-t border-border/30 bg-muted/15 px-6 py-4">
            <Button
              type="button"
              variant="outline"
              onClick={handleClose}
              disabled={submitting}
              className="rounded-lg"
            >
              取消
            </Button>
            <Button type="submit" disabled={submitting} className="rounded-lg">
              {submitting ? (
                <>
                  <Loader2 className="animate-spin" />
                  {intent === "reparse" ? "重新生成中..." : "生成中..."}
                </>
              ) : (
                <>
                  <Sparkles />
                  {intent === "reparse" ? "删除并重新生成" : "开始生成"}
                </>
              )}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
