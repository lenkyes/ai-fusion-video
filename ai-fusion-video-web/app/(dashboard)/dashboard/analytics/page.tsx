"use client";

import { useEffect, useMemo, useState } from "react";
import type { ReactNode } from "react";
import { useRouter } from "next/navigation";
import {
  Activity,
  BarChart3,
  CheckCircle2,
  Clock3,
  Film,
  ImageIcon,
  Layers3,
  Loader2,
  Package,
  RefreshCw,
  ServerCog,
  TriangleAlert,
} from "lucide-react";
import { motion } from "framer-motion";
import { dashboardApi, type DashboardAnalytics, type GenerationTask } from "@/lib/api/dashboard";
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

const statusMap: Record<number, { label: string; className: string }> = {
  0: { label: "排队中", className: "bg-amber-500/10 text-amber-400" },
  1: { label: "处理中", className: "bg-blue-500/10 text-blue-400" },
  2: { label: "已完成", className: "bg-green-500/10 text-green-400" },
  3: { label: "失败", className: "bg-red-500/10 text-red-400" },
};

function formatNumber(value: number | null | undefined) {
  return new Intl.NumberFormat("zh-CN").format(value ?? 0);
}

function formatDuration(seconds: number | null | undefined) {
  if (!seconds) return "-";
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m`;
  const hours = Math.floor(minutes / 60);
  return `${hours}h ${minutes % 60}m`;
}

function formatTime(value: string | null | undefined) {
  if (!value) return "-";
  return new Date(value).toLocaleString("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}

export default function AnalyticsPage() {
  const router = useRouter();
  const [data, setData] = useState<DashboardAnalytics | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = async (silent = false) => {
    if (silent) {
      setRefreshing(true);
    } else {
      setLoading(true);
    }
    try {
      const resp = await dashboardApi.analytics();
      setData(resp);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "加载失败");
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  };

  useEffect(() => {
    void load();
  }, []);

  const maxDaily = useMemo(() => {
    if (!data?.dailyActivity.length) return 1;
    return Math.max(
      1,
      ...data.dailyActivity.map((item) => item.imageTasks + item.videoTasks)
    );
  }, [data]);

  if (loading) {
    return (
      <div className="flex items-center justify-center py-32">
        <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
      </div>
    );
  }

  if (!data) {
    return (
      <div className="max-w-[1200px] py-24 text-center">
        <TriangleAlert className="mx-auto mb-3 h-8 w-8 text-red-400" />
        <p className="text-sm text-muted-foreground">{error || "数据加载失败"}</p>
        <Button className="mt-4" variant="outline" onClick={() => load()}>
          <RefreshCw />
          重新加载
        </Button>
      </div>
    );
  }

  const overview = data.overview;

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
            <BarChart3 className="h-6 w-6 text-purple-400" />
            数据分析
          </h1>
          <p className="mt-1 text-sm text-muted-foreground">
            创作资产、生成任务和队列压力的实时概览
          </p>
        </div>
        <Button variant="outline" onClick={() => load(true)} disabled={refreshing}>
          <RefreshCw className={cn(refreshing && "animate-spin")} />
          刷新
        </Button>
      </motion.div>

      <motion.div variants={itemVariants} className="mb-6 grid grid-cols-2 gap-3 lg:grid-cols-4">
        <MetricCard label="项目" value={overview.projectCount} icon={Film} color="text-blue-400" bg="bg-blue-500/10" />
        <MetricCard label="分镜镜头" value={overview.storyboardItemCount} icon={Layers3} color="text-cyan-400" bg="bg-cyan-500/10" />
        <MetricCard label="素材资产" value={overview.assetCount} icon={Package} color="text-orange-400" bg="bg-orange-500/10" />
        <MetricCard label="任务完成率" value={`${overview.completionRate}%`} icon={CheckCircle2} color="text-green-400" bg="bg-green-500/10" />
      </motion.div>

      <motion.div variants={itemVariants} className="mb-6 grid gap-3 lg:grid-cols-[1.15fr_0.85fr]">
        <Panel title="生成概况" icon={<Activity className="h-4 w-4 text-primary" />}>
          <div className="grid gap-3 md:grid-cols-2">
            {data.generationStats.map((stat) => (
              <div key={stat.type} className="rounded-xl border border-border/30 bg-background/30 p-4">
                <div className="mb-4 flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <div className={cn(
                      "flex h-8 w-8 items-center justify-center rounded-lg",
                      stat.type === "image" ? "bg-pink-500/10 text-pink-400" : "bg-blue-500/10 text-blue-400"
                    )}>
                      {stat.type === "image" ? <ImageIcon className="h-4 w-4" /> : <Film className="h-4 w-4" />}
                    </div>
                    <span className="text-sm font-medium">{stat.label}</span>
                  </div>
                  <span className="text-xl font-bold">{formatNumber(stat.total)}</span>
                </div>
                <div className="grid grid-cols-4 gap-2 text-center">
                  <MiniStat label="排队" value={stat.queued} />
                  <MiniStat label="运行" value={stat.running} />
                  <MiniStat label="完成" value={stat.completed} />
                  <MiniStat label="失败" value={stat.failed} danger />
                </div>
                <div className="mt-4 flex items-center justify-between border-t border-border/20 pt-3 text-xs text-muted-foreground">
                  <span>产出 {formatNumber(stat.outputCount)}</span>
                  <span>均耗时 {formatDuration(stat.averageCompletedSeconds)}</span>
                </div>
              </div>
            ))}
          </div>
        </Panel>

        <Panel title="近 7 天任务" icon={<Clock3 className="h-4 w-4 text-green-400" />}>
          <div className="flex h-[190px] items-end gap-2">
            {data.dailyActivity.map((item) => {
              const total = item.imageTasks + item.videoTasks;
              const height = total > 0 ? Math.max(18, (total / maxDaily) * 100) : 4;
              const date = new Date(item.date);
              return (
                <div key={item.date} className="flex flex-1 flex-col items-center gap-2">
                  <div className="flex h-32 w-full items-end rounded-lg bg-muted/20 px-1">
                    <div
                      className={cn(
                        "w-full rounded-md transition-all",
                        item.failedTasks > 0 ? "bg-red-400/60" : "bg-primary/60"
                      )}
                      style={{ height: `${height}%` }}
                    />
                  </div>
                  <span className="text-[10px] text-muted-foreground">
                    {date.toLocaleDateString("zh-CN", { weekday: "short" }).replace("周", "")}
                  </span>
                  <span className="text-xs font-medium">{total}</span>
                </div>
              );
            })}
          </div>
        </Panel>
      </motion.div>

      <motion.div variants={itemVariants} className="mb-6">
        <Panel title="队列压力" icon={<ServerCog className="h-4 w-4 text-cyan-400" />}>
          <div className="overflow-x-auto">
            <table className="w-full min-w-[680px] text-sm">
              <thead className="text-xs text-muted-foreground">
                <tr className="border-b border-border/20">
                  <th className="py-2 text-left font-medium">类型</th>
                  <th className="py-2 text-left font-medium">模型</th>
                  <th className="py-2 text-right font-medium">排队</th>
                  <th className="py-2 text-right font-medium">运行</th>
                  <th className="py-2 text-right font-medium">并发上限</th>
                </tr>
              </thead>
              <tbody>
                {data.queues.length === 0 ? (
                  <tr>
                    <td colSpan={5} className="py-8 text-center text-sm text-muted-foreground">
                      暂无生成队列
                    </td>
                  </tr>
                ) : data.queues.map((queue) => (
                  <tr key={queue.queueName} className="border-b border-border/10 last:border-0">
                    <td className="py-3">
                      <TypeBadge type={queue.type} />
                    </td>
                    <td className="max-w-[360px] truncate py-3">{queue.modelName || queue.queueName}</td>
                    <td className="py-3 text-right tabular-nums">{queue.pendingCount}</td>
                    <td className="py-3 text-right tabular-nums">{queue.runningCount}</td>
                    <td className="py-3 text-right tabular-nums">{queue.maxConcurrent}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Panel>
      </motion.div>

      <motion.div variants={itemVariants}>
        <Panel
          title="最近任务"
          icon={<Clock3 className="h-4 w-4 text-amber-400" />}
          action={<Button variant="outline" size="sm" onClick={() => router.push("/dashboard/tasks")}>任务中心</Button>}
        >
          <TaskTable tasks={data.recentTasks} />
        </Panel>
      </motion.div>
    </motion.div>
  );
}

function MetricCard({
  label,
  value,
  icon: Icon,
  color,
  bg,
}: {
  label: string;
  value: number | string;
  icon: typeof Film;
  color: string;
  bg: string;
}) {
  return (
    <div className="rounded-xl border border-border/30 bg-card/50 p-4 backdrop-blur-sm">
      <div className="mb-3 flex items-center gap-2">
        <div className={cn("flex h-8 w-8 items-center justify-center rounded-lg", bg)}>
          <Icon className={cn("h-4 w-4", color)} />
        </div>
        <span className="text-xs text-muted-foreground">{label}</span>
      </div>
      <p className="text-2xl font-bold tracking-tight">
        {typeof value === "number" ? formatNumber(value) : value}
      </p>
    </div>
  );
}

function MiniStat({ label, value, danger }: { label: string; value: number; danger?: boolean }) {
  return (
    <div className="rounded-lg bg-muted/20 px-2 py-2">
      <p className={cn("text-sm font-semibold", danger && value > 0 && "text-red-400")}>{formatNumber(value)}</p>
      <p className="mt-0.5 text-[10px] text-muted-foreground">{label}</p>
    </div>
  );
}

function Panel({
  title,
  icon,
  action,
  children,
}: {
  title: string;
  icon: ReactNode;
  action?: ReactNode;
  children: ReactNode;
}) {
  return (
    <section className="rounded-xl border border-border/30 bg-card/50 p-4 backdrop-blur-sm">
      <div className="mb-4 flex items-center justify-between gap-3">
        <h2 className="flex items-center gap-2 text-sm font-semibold text-foreground/85">
          {icon}
          {title}
        </h2>
        {action}
      </div>
      {children}
    </section>
  );
}

function TaskTable({ tasks }: { tasks: GenerationTask[] }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[760px] text-sm">
        <thead className="text-xs text-muted-foreground">
          <tr className="border-b border-border/20">
            <th className="py-2 text-left font-medium">类型</th>
            <th className="py-2 text-left font-medium">提示词</th>
            <th className="py-2 text-left font-medium">模型</th>
            <th className="py-2 text-left font-medium">状态</th>
            <th className="py-2 text-right font-medium">时间</th>
          </tr>
        </thead>
        <tbody>
          {tasks.length === 0 ? (
            <tr>
              <td colSpan={5} className="py-8 text-center text-muted-foreground">暂无生成任务</td>
            </tr>
          ) : tasks.map((task) => (
            <tr key={`${task.type}-${task.id}`} className="border-b border-border/10 last:border-0">
              <td className="py-3"><TypeBadge type={task.type} /></td>
              <td className="max-w-[360px] truncate py-3">{task.prompt || "-"}</td>
              <td className="max-w-[180px] truncate py-3 text-muted-foreground">{task.modelName || "-"}</td>
              <td className="py-3"><StatusBadge status={task.status} /></td>
              <td className="py-3 text-right text-xs text-muted-foreground">{formatTime(task.createTime)}</td>
            </tr>
          ))}
        </tbody>
      </table>
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
