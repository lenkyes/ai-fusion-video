"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useParams } from "next/navigation";
import {
  AlertTriangle,
  BarChart3,
  Calculator,
  CheckCircle2,
  Image as ImageIcon,
  Loader2,
  Save,
  Video,
} from "lucide-react";
import { cn } from "@/lib/utils";
import {
  costAnalysisApi,
  type CostBillingMode,
  type CostConfigRow,
  type CostMediaType,
  type ProjectCostSummary,
} from "@/lib/api/cost-analysis";
import { useProject } from "../project-context";

const billingLabels: Record<CostBillingMode, string> = {
  per_image: "每张图",
  per_second: "每秒视频",
  per_video: "每条视频",
  free: "免费",
};

function rowKey(row: Pick<CostConfigRow, "modelId" | "mediaType">) {
  return `${row.modelId}:${row.mediaType}`;
}

function billingOptions(mediaType: CostMediaType): CostBillingMode[] {
  return mediaType === "image" ? ["per_image", "free"] : ["per_second", "per_video", "free"];
}

function money(value: number | string | null | undefined, digits = 4) {
  const amount = Number(value ?? 0);
  return `¥${amount.toFixed(digits)}`;
}

function mediaLabel(mediaType: CostMediaType) {
  return mediaType === "image" ? "图片" : "视频";
}

function mediaTone(mediaType: CostMediaType) {
  return mediaType === "image"
    ? "bg-cyan-500/10 text-cyan-600 border-cyan-500/25"
    : "bg-violet-500/10 text-violet-600 border-violet-500/25";
}

export default function ProjectCostsPage() {
  const params = useParams();
  const projectId = Number(params.id);
  const { project } = useProject();

  const [summary, setSummary] = useState<ProjectCostSummary | null>(null);
  const [configs, setConfigs] = useState<CostConfigRow[]>([]);
  const [selectedKeys, setSelectedKeys] = useState<string[]>([]);
  const [dirtyKeys, setDirtyKeys] = useState<string[]>([]);
  const [batchBillingMode, setBatchBillingMode] = useState<CostBillingMode>("per_second");
  const [batchPrice, setBatchPrice] = useState("0");
  const [batchEnabled, setBatchEnabled] = useState(true);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  const selectedSet = useMemo(() => new Set(selectedKeys), [selectedKeys]);
  const dirtySet = useMemo(() => new Set(dirtyKeys), [dirtyKeys]);
  const maxModelCost = useMemo(
    () => Math.max(1, ...(summary?.modelCosts ?? []).map((item) => Number(item.cost || 0))),
    [summary]
  );

  const loadData = useCallback(async () => {
    setLoading(true);
    try {
      const [nextSummary, nextConfigs] = await Promise.all([
        costAnalysisApi.getProjectSummary(projectId),
        costAnalysisApi.listConfigs(),
      ]);
      setSummary(nextSummary);
      setConfigs(nextConfigs);
      setDirtyKeys([]);
      setMessage(null);
    } catch (err) {
      setMessage(err instanceof Error ? err.message : "加载成本数据失败");
    } finally {
      setLoading(false);
    }
  }, [projectId]);

  useEffect(() => {
    loadData();
  }, [loadData]);

  const markDirty = (key: string) => {
    setDirtyKeys((prev) => (prev.includes(key) ? prev : [...prev, key]));
  };

  const toggleSelected = (key: string, checked: boolean) => {
    setSelectedKeys((prev) => {
      if (checked) {
        return prev.includes(key) ? prev : [...prev, key];
      }
      return prev.filter((item) => item !== key);
    });
  };

  const updateConfig = (key: string, patch: Partial<CostConfigRow>) => {
    setConfigs((prev) =>
      prev.map((row) =>
        rowKey(row) === key
          ? {
              ...row,
              ...patch,
              configured: true,
            }
          : row
      )
    );
    markDirty(key);
  };

  const applyBatch = () => {
    if (selectedKeys.length === 0) {
      setMessage("请先选择要批量编辑的模型");
      return;
    }
    const price = Number(batchPrice || 0);
    setConfigs((prev) =>
      prev.map((row) => {
        const key = rowKey(row);
        if (!selectedSet.has(key)) {
          return row;
        }
        const mode = billingOptions(row.mediaType).includes(batchBillingMode)
          ? batchBillingMode
          : row.mediaType === "image"
            ? "per_image"
            : "per_second";
        return {
          ...row,
          billingMode: mode,
          unitPrice: Number.isFinite(price) && price >= 0 ? price : 0,
          enabled: batchEnabled,
          configured: true,
        };
      })
    );
    setDirtyKeys((prev) => Array.from(new Set([...prev, ...selectedKeys])));
    setMessage(`已应用到 ${selectedKeys.length} 个模型，保存后生效`);
  };

  const saveConfigs = async () => {
    const rows = configs.filter((row) => dirtySet.has(rowKey(row)));
    if (rows.length === 0) {
      setMessage("没有需要保存的价格变更");
      return;
    }
    setSaving(true);
    try {
      await costAnalysisApi.batchUpdateConfigs(
        rows.map((row) => ({
          modelId: row.modelId,
          mediaType: row.mediaType,
          billingMode: row.billingMode,
          unitPrice: Number(row.unitPrice || 0),
          currency: row.currency || "CNY",
          enabled: row.enabled,
          remark: row.remark,
        }))
      );
      setMessage(`已保存 ${rows.length} 条价格配置`);
      await loadData();
    } catch (err) {
      setMessage(err instanceof Error ? err.message : "保存价格配置失败");
    } finally {
      setSaving(false);
    }
  };

  const selectAll = () => {
    setSelectedKeys(configs.map(rowKey));
  };

  const clearSelection = () => {
    setSelectedKeys([]);
  };

  if (loading && !summary) {
    return (
      <div className="flex items-center justify-center py-24">
        <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
      </div>
    );
  }

  return (
    <div className="mx-auto w-full max-w-7xl px-5 py-6 space-y-6">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <BarChart3 className="h-4 w-4 text-emerald-500" />
            成本分析
          </div>
          <h1 className="mt-1 text-2xl font-semibold tracking-normal">
            {project?.name || "项目"} 生成成本
          </h1>
        </div>
        <button
          type="button"
          onClick={loadData}
          className="inline-flex items-center justify-center gap-1.5 rounded-lg border border-border/40 bg-background px-3 py-2 text-xs font-medium transition-colors hover:bg-muted/50"
        >
          {loading ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Calculator className="h-3.5 w-3.5" />}
          重新计算
        </button>
      </div>

      {message && (
        <div className="flex items-center gap-2 rounded-lg border border-border/40 bg-muted/30 px-3 py-2 text-sm text-muted-foreground">
          <AlertTriangle className="h-4 w-4 text-amber-500" />
          {message}
        </div>
      )}

      {summary && (
        <>
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-5">
            <Metric title="总成本" value={money(summary.totalCost)} icon={Calculator} tone="text-emerald-500" />
            <Metric title="图片成本" value={money(summary.imageCost)} detail={`${summary.imageSuccessCount} 张成功图`} icon={ImageIcon} tone="text-cyan-500" />
            <Metric title="视频成本" value={money(summary.videoCost)} detail={`${summary.videoSuccessCount} 条 / ${summary.videoSuccessSeconds}s`} icon={Video} tone="text-violet-500" />
            <Metric title="每秒成本" value={money(summary.costPerFinalSecond, 4)} detail="按成功视频秒数" icon={BarChart3} tone="text-blue-500" />
            <Metric title="未配置产出" value={`${summary.unpricedImageCount + summary.unpricedVideoCount}`} detail={`${summary.manualUploadVideoCount} 条手动上传`} icon={AlertTriangle} tone="text-amber-500" />
          </div>

          <div className="grid gap-5 xl:grid-cols-[1.1fr_0.9fr]">
            <section className="rounded-xl border border-border/35 bg-card/40 p-4">
              <div className="mb-3 flex items-center justify-between">
                <h2 className="text-sm font-semibold">模型成本</h2>
                <span className="text-xs text-muted-foreground">{summary.modelCosts.length} 个模型</span>
              </div>
              <div className="space-y-3">
                {summary.modelCosts.length === 0 ? (
                  <EmptyText text="暂无成功生成产出" />
                ) : (
                  summary.modelCosts.map((item) => (
                    <div key={`${item.modelId}-${item.mediaType}`} className="space-y-1.5">
                      <div className="flex items-center gap-2 text-xs">
                        <span className={cn("rounded-md border px-1.5 py-0.5 font-medium", mediaTone(item.mediaType))}>
                          {mediaLabel(item.mediaType)}
                        </span>
                        <span className="font-medium truncate">{item.modelName}</span>
                        <span className="ml-auto font-semibold">{money(item.cost)}</span>
                      </div>
                      <div className="h-2 overflow-hidden rounded-full bg-muted">
                        <div
                          className="h-full rounded-full bg-emerald-500/75"
                          style={{ width: `${Math.max(4, (Number(item.cost || 0) / maxModelCost) * 100)}%` }}
                        />
                      </div>
                      <div className="flex flex-wrap gap-x-3 gap-y-1 text-[11px] text-muted-foreground">
                        <span>{item.taskCount} 个任务</span>
                        <span>{item.successCount} 个成功产出</span>
                        {item.mediaType === "video" && <span>{item.successSeconds}s</span>}
                        <span>{billingLabels[item.billingMode]} {money(item.unitPrice, 6)}</span>
                        {item.unpricedCount > 0 && <span className="text-amber-600">{item.unpricedCount} 个未计价</span>}
                      </div>
                    </div>
                  ))
                )}
              </div>
            </section>

            <section className="rounded-xl border border-border/35 bg-card/40 p-4">
              <div className="mb-3 flex items-center justify-between">
                <h2 className="text-sm font-semibold">镜头成本 Top 20</h2>
                <span className="text-xs text-muted-foreground">{summary.shotCosts.length} 个镜头</span>
              </div>
              <div className="overflow-x-auto">
                <table className="w-full min-w-[520px] text-xs">
                  <thead className="text-muted-foreground">
                    <tr className="border-b border-border/30 text-left">
                      <th className="py-2 font-medium">镜头ID</th>
                      <th className="py-2 font-medium">图片</th>
                      <th className="py-2 font-medium">视频</th>
                      <th className="py-2 font-medium">未计价</th>
                      <th className="py-2 text-right font-medium">成本</th>
                    </tr>
                  </thead>
                  <tbody>
                    {summary.shotCosts.slice(0, 20).map((shot) => (
                      <tr key={shot.storyboardItemId} className="border-b border-border/15">
                        <td className="py-2 font-medium">#{shot.storyboardItemId}</td>
                        <td className="py-2">{shot.imageSuccessCount} 张</td>
                        <td className="py-2">{shot.videoSuccessCount} 条 / {shot.videoSuccessSeconds}s</td>
                        <td className="py-2 text-amber-600">{shot.unpricedImageCount + shot.unpricedVideoCount}</td>
                        <td className="py-2 text-right font-semibold">{money(shot.totalCost)}</td>
                      </tr>
                    ))}
                    {summary.shotCosts.length === 0 && (
                      <tr>
                        <td colSpan={5} className="py-8">
                          <EmptyText text="暂无镜头级成本" />
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
            </section>
          </div>
        </>
      )}

      <section className="rounded-xl border border-border/35 bg-card/40 p-4">
        <div className="mb-4 flex flex-col gap-3 lg:flex-row lg:items-end lg:justify-between">
          <div>
            <h2 className="text-sm font-semibold">模型价格配置</h2>
            <p className="mt-1 text-xs text-muted-foreground">
              价格只用于成功产出统计；失败任务、无 URL 结果和手动上传默认 0 成本。
            </p>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <button type="button" onClick={selectAll} className="rounded-lg border border-border/35 px-3 py-1.5 text-xs hover:bg-muted/40">
              全选
            </button>
            <button type="button" onClick={clearSelection} className="rounded-lg border border-border/35 px-3 py-1.5 text-xs hover:bg-muted/40">
              清空
            </button>
            <button
              type="button"
              onClick={saveConfigs}
              disabled={saving}
              className="inline-flex items-center gap-1.5 rounded-lg border border-emerald-500/30 bg-emerald-500/10 px-3 py-1.5 text-xs font-medium text-emerald-600 hover:bg-emerald-500/20 disabled:opacity-60"
            >
              {saving ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Save className="h-3.5 w-3.5" />}
              保存价格
            </button>
          </div>
        </div>

        <div className="mb-4 flex flex-wrap items-end gap-2 rounded-lg border border-border/30 bg-muted/20 p-3">
          <div className="space-y-1">
            <label className="text-[11px] text-muted-foreground">批量计费</label>
            <select
              value={batchBillingMode}
              onChange={(event) => setBatchBillingMode(event.target.value as CostBillingMode)}
              className="h-9 rounded-lg border border-border/40 bg-background px-2 text-xs outline-none"
            >
              <option value="per_image">每张图</option>
              <option value="per_second">每秒视频</option>
              <option value="per_video">每条视频</option>
              <option value="free">免费</option>
            </select>
          </div>
          <div className="space-y-1">
            <label className="text-[11px] text-muted-foreground">批量单价</label>
            <input
              type="number"
              min={0}
              step="0.000001"
              value={batchPrice}
              onChange={(event) => setBatchPrice(event.target.value)}
              className="h-9 w-28 rounded-lg border border-border/40 bg-background px-2 text-xs outline-none"
            />
          </div>
          <label className="flex h-9 items-center gap-2 rounded-lg border border-border/40 bg-background px-3 text-xs">
            <input
              type="checkbox"
              checked={batchEnabled}
              onChange={(event) => setBatchEnabled(event.target.checked)}
              className="h-4 w-4 accent-emerald-500"
            />
            启用
          </label>
          <button
            type="button"
            onClick={applyBatch}
            className="inline-flex h-9 items-center gap-1.5 rounded-lg border border-primary/30 bg-primary/10 px-3 text-xs font-medium text-primary hover:bg-primary/20"
          >
            <CheckCircle2 className="h-3.5 w-3.5" />
            应用到选中
          </button>
          <span className="text-xs text-muted-foreground">{selectedKeys.length} 个已选</span>
        </div>

        <div className="overflow-x-auto">
          <table className="w-full min-w-[860px] text-xs">
            <thead className="text-muted-foreground">
              <tr className="border-b border-border/30 text-left">
                <th className="py-2 font-medium"></th>
                <th className="py-2 font-medium">模型</th>
                <th className="py-2 font-medium">类型</th>
                <th className="py-2 font-medium">计费方式</th>
                <th className="py-2 font-medium">单价</th>
                <th className="py-2 font-medium">状态</th>
                <th className="py-2 font-medium">配置</th>
              </tr>
            </thead>
            <tbody>
              {configs.map((row) => {
                const key = rowKey(row);
                const isDirty = dirtySet.has(key);
                return (
                  <tr key={key} className="border-b border-border/15">
                    <td className="py-2 pr-2">
                      <input
                        type="checkbox"
                        checked={selectedSet.has(key)}
                        onChange={(event) => toggleSelected(key, event.target.checked)}
                        className="h-4 w-4 accent-emerald-500"
                      />
                    </td>
                    <td className="py-2 pr-3">
                      <div className="font-medium">{row.modelName}</div>
                      <div className="mt-0.5 text-[11px] text-muted-foreground">{row.modelCode || "未设置 code"}</div>
                    </td>
                    <td className="py-2 pr-3">
                      <span className={cn("rounded-md border px-1.5 py-0.5 font-medium", mediaTone(row.mediaType))}>
                        {mediaLabel(row.mediaType)}
                      </span>
                    </td>
                    <td className="py-2 pr-3">
                      <select
                        value={row.billingMode}
                        onChange={(event) => updateConfig(key, { billingMode: event.target.value as CostBillingMode })}
                        className="h-8 rounded-lg border border-border/40 bg-background px-2 text-xs outline-none"
                      >
                        {billingOptions(row.mediaType).map((mode) => (
                          <option key={mode} value={mode}>{billingLabels[mode]}</option>
                        ))}
                      </select>
                    </td>
                    <td className="py-2 pr-3">
                      <input
                        type="number"
                        min={0}
                        step="0.000001"
                        value={row.unitPrice}
                        onChange={(event) => updateConfig(key, { unitPrice: Number(event.target.value || 0) })}
                        className="h-8 w-28 rounded-lg border border-border/40 bg-background px-2 text-xs outline-none"
                      />
                    </td>
                    <td className="py-2 pr-3">
                      <label className="inline-flex items-center gap-2">
                        <input
                          type="checkbox"
                          checked={row.enabled}
                          onChange={(event) => updateConfig(key, { enabled: event.target.checked })}
                          className="h-4 w-4 accent-emerald-500"
                        />
                        <span>{row.enabled ? "启用" : "停用"}</span>
                      </label>
                    </td>
                    <td className="py-2">
                      <span
                        className={cn(
                          "rounded-md px-1.5 py-0.5 text-[11px]",
                          isDirty
                            ? "bg-blue-500/10 text-blue-600"
                            : row.configured
                              ? "bg-emerald-500/10 text-emerald-600"
                              : "bg-muted text-muted-foreground"
                        )}
                      >
                        {isDirty ? "未保存" : row.configured ? "已配置" : "未配置"}
                      </span>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </section>
    </div>
  );
}

function Metric({
  title,
  value,
  detail,
  icon: Icon,
  tone,
}: {
  title: string;
  value: string;
  detail?: string;
  icon: typeof Calculator;
  tone: string;
}) {
  return (
    <div className="rounded-xl border border-border/35 bg-card/40 p-4">
      <div className="flex items-center justify-between gap-2">
        <span className="text-xs text-muted-foreground">{title}</span>
        <Icon className={cn("h-4 w-4", tone)} />
      </div>
      <div className="mt-2 text-xl font-semibold">{value}</div>
      {detail && <div className="mt-1 text-[11px] text-muted-foreground">{detail}</div>}
    </div>
  );
}

function EmptyText({ text }: { text: string }) {
  return (
    <div className="flex items-center justify-center rounded-lg border border-dashed border-border/30 py-8 text-xs text-muted-foreground">
      {text}
    </div>
  );
}
