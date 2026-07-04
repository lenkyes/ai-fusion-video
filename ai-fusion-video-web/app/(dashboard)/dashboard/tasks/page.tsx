"use client";

import { useCallback, useEffect, useState } from "react";
import {
  Clock3,
  Film,
  ImageIcon,
  Loader2,
  RefreshCw,
  RotateCcw,
  SearchX,
  ServerCog,
  TriangleAlert,
} from "lucide-react";
import { motion } from "framer-motion";
import { toast } from "sonner";
import { dashboardApi, type GenerationTask } from "@/lib/api/dashboard";
import type { PageResult } from "@/lib/api/types";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";

const containerVariants = {
  hidden: { opacity: 0 },
  visible: {
    opacity: 1,
    transition: { staggerChildren: 0.06, delayChildren: 0.08 },
  },
};

const itemVariants = {
  hidden: { opacity: 0, y: 16 },
  visible: {
    opacity: 1,
    y: 0,
    transition: { duration: 0.45, ease: [0.25, 0.46, 0.45, 0.94] as [number, number, number, number] },
  },
};

const typeTabs = [
  { value: "all", label: "全部" },
  { value: "image", label: "图片" },
  { value: "video", label: "视频" },
] as const;

const statusTabs = [
  { value: "all", label: "全部" },
  { value: 0, label: "排队" },
  { value: 1, label: "运行" },
  { value: 2, label: "完成" },
  { value: 3, label: "失败" },
] as const;

type TypeFilter = (typeof typeTabs)[number]["value"];
type StatusFilter = (typeof statusTabs)[number]["value"];

const statusMap: Record<number, { label: string; className: string }> = {
  0: { label: "排队中", className: "bg-amber-500/10 text-amber-400" },
  1: { label: "处理中", className: "bg-blue-500/10 text-blue-400" },
  2: { label: "已完成", className: "bg-green-500/10 text-green-400" },
  3: { label: "失败", className: "bg-red-500/10 text-red-400" },
};

const pageSize = 20;

function formatTime(value: string | null | undefined) {
  if (!value) return "-";
  return new Date(value).toLocaleString("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function formatDuration(seconds: number | null | undefined) {
  if (!seconds) return "-";
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m`;
  const hours = Math.floor(minutes / 60);
  return `${hours}h ${minutes % 60}m`;
}

export default function TasksPage() {
  const [type, setType] = useState<TypeFilter>("all");
  const [status, setStatus] = useState<StatusFilter>("all");
  const [pageNo, setPageNo] = useState(1);
  const [data, setData] = useState<PageResult<GenerationTask> | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [actingId, setActingId] = useState<string | null>(null);

  const load = useCallback(async (silent = false) => {
    if (silent) {
      setRefreshing(true);
    } else {
      setLoading(true);
    }
    try {
      const resp = await dashboardApi.tasks({ pageNo, pageSize, type, status });
      setData(resp);
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "任务加载失败");
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [pageNo, status, type]);

  useEffect(() => {
    void load();
  }, [load]);

  const handleTypeChange = (next: TypeFilter) => {
    setType(next);
    setPageNo(1);
  };

  const handleStatusChange = (next: StatusFilter) => {
    setStatus(next);
    setPageNo(1);
  };

  const retryTask = async (task: GenerationTask) => {
    setActingId(`${task.type}-${task.id}`);
    try {
      await dashboardApi.retryTask(task.type, task.id);
      toast.success("任务已重新入队");
      await load(true);
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "重试失败");
    } finally {
      setActingId(null);
    }
  };

  const recoverStaleTasks = async () => {
    setActingId("recover");
    try {
      const resp = await dashboardApi.recoverStaleTasks();
      toast.success(resp.totalRecovered > 0
        ? `已恢复 ${resp.totalRecovered} 个任务`
        : "没有发现卡住的任务");
      await load(true);
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "恢复失败");
    } finally {
      setActingId(null);
    }
  };

  const tasks = data?.list ?? [];
  const total = data?.total ?? 0;
  const totalPages = Math.max(1, Math.ceil(total / pageSize));

  return (
    <motion.div
      className="max-w-[1200px] pb-12"
      variants={containerVariants}
      initial="hidden"
      animate="visible"
    >
      <motion.div variants={itemVariants} className="mb-8 flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="flex items-center gap-2 text-2xl font-bold tracking-tight">
            <ServerCog className="h-6 w-6 text-cyan-400" />
            任务中心
          </h1>
          <p className="mt-1 text-sm text-muted-foreground">
            查看生成队列、失败原因，并重试需要处理的任务
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button variant="outline" onClick={recoverStaleTasks} disabled={actingId === "recover"}>
            <RotateCcw className={cn(actingId === "recover" && "animate-spin")} />
            恢复卡住任务
          </Button>
          <Button variant="outline" onClick={() => load(true)} disabled={refreshing}>
            <RefreshCw className={cn(refreshing && "animate-spin")} />
            刷新
          </Button>
        </div>
      </motion.div>

      <motion.div variants={itemVariants} className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <Segmented
          items={typeTabs}
          value={type}
          onChange={handleTypeChange}
        />
        <Segmented
          items={statusTabs}
          value={status}
          onChange={handleStatusChange}
        />
      </motion.div>

      <motion.div variants={itemVariants} className="rounded-xl border border-border/30 bg-card/50 backdrop-blur-sm">
        {loading ? (
          <div className="flex items-center justify-center py-24">
            <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
          </div>
        ) : tasks.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-24 text-center">
            <SearchX className="mb-3 h-8 w-8 text-muted-foreground/50" />
            <p className="text-sm font-medium">暂无匹配任务</p>
            <p className="mt-1 text-xs text-muted-foreground">调整筛选条件后再查看</p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[980px] text-sm">
              <thead className="text-xs text-muted-foreground">
                <tr className="border-b border-border/20">
                  <th className="px-4 py-3 text-left font-medium">类型</th>
                  <th className="px-4 py-3 text-left font-medium">提示词</th>
                  <th className="px-4 py-3 text-left font-medium">模型</th>
                  <th className="px-4 py-3 text-left font-medium">状态</th>
                  <th className="px-4 py-3 text-right font-medium">产出</th>
                  <th className="px-4 py-3 text-right font-medium">耗时</th>
                  <th className="px-4 py-3 text-left font-medium">创建时间</th>
                  <th className="px-4 py-3 text-right font-medium">操作</th>
                </tr>
              </thead>
              <tbody>
                {tasks.map((task) => (
                  <tr key={`${task.type}-${task.id}`} className="border-b border-border/10 last:border-0">
                    <td className="px-4 py-3">
                      <TypeBadge type={task.type} />
                    </td>
                    <td className="max-w-[340px] px-4 py-3">
                      <div className="truncate font-medium">{task.prompt || "-"}</div>
                      {task.errorMsg && (
                        <div className="mt-1 flex items-center gap-1 text-xs text-red-400">
                          <TriangleAlert className="h-3 w-3 shrink-0" />
                          <span className="truncate">{task.errorMsg}</span>
                        </div>
                      )}
                    </td>
                    <td className="max-w-[180px] truncate px-4 py-3 text-muted-foreground">
                      {task.modelName || "-"}
                    </td>
                    <td className="px-4 py-3">
                      <StatusBadge status={task.status} />
                    </td>
                    <td className="px-4 py-3 text-right tabular-nums">
                      {task.successCount ?? 0}/{task.count ?? 1}
                    </td>
                    <td className="px-4 py-3 text-right text-muted-foreground tabular-nums">
                      {formatDuration(task.durationSeconds)}
                    </td>
                    <td className="px-4 py-3 text-muted-foreground">
                      <span className="inline-flex items-center gap-1.5 whitespace-nowrap">
                        <Clock3 className="h-3.5 w-3.5" />
                        {formatTime(task.createTime)}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-right">
                      {task.canRetry ? (
                        <Button
                          variant="outline"
                          size="sm"
                          onClick={() => retryTask(task)}
                          disabled={actingId === `${task.type}-${task.id}`}
                        >
                          <RefreshCw className={cn(actingId === `${task.type}-${task.id}` && "animate-spin")} />
                          重试
                        </Button>
                      ) : (
                        <span className="text-xs text-muted-foreground/50">-</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </motion.div>

      <motion.div variants={itemVariants} className="mt-4 flex items-center justify-between text-sm text-muted-foreground">
        <span>共 {total} 个任务</span>
        <div className="flex items-center gap-2">
          <Button variant="outline" size="sm" disabled={pageNo <= 1} onClick={() => setPageNo((p) => Math.max(1, p - 1))}>
            上一页
          </Button>
          <span className="min-w-16 text-center tabular-nums">
            {pageNo}/{totalPages}
          </span>
          <Button variant="outline" size="sm" disabled={pageNo >= totalPages} onClick={() => setPageNo((p) => Math.min(totalPages, p + 1))}>
            下一页
          </Button>
        </div>
      </motion.div>
    </motion.div>
  );
}

function Segmented<T extends string | number>({
  items,
  value,
  onChange,
}: {
  items: readonly { value: T; label: string }[];
  value: T;
  onChange: (value: T) => void;
}) {
  return (
    <div className="inline-flex rounded-full border border-border/30 bg-card/50 p-1">
      {items.map((item) => {
        const active = item.value === value;
        return (
          <button
            key={String(item.value)}
            onClick={() => onChange(item.value)}
            className={cn(
              "h-8 rounded-full px-3 text-sm transition-colors",
              active ? "bg-primary text-primary-foreground" : "text-muted-foreground hover:text-foreground"
            )}
          >
            {item.label}
          </button>
        );
      })}
    </div>
  );
}

function TypeBadge({ type }: { type: "image" | "video" }) {
  return (
    <Badge variant="outline" className={cn(
      type === "image" ? "border-pink-400/20 text-pink-400" : "border-blue-400/20 text-blue-400"
    )}>
      {type === "image" ? <ImageIcon /> : <Film />}
      {type === "image" ? "图片" : "视频"}
    </Badge>
  );
}

function StatusBadge({ status }: { status: number | null }) {
  const conf = statusMap[status ?? -1] ?? { label: "未知", className: "bg-muted text-muted-foreground" };
  return <span className={cn("inline-flex h-5 items-center rounded-full px-2 text-xs font-medium", conf.className)}>{conf.label}</span>;
}
