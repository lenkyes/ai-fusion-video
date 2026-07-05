"use client";

import { useEffect, useState, useRef, useCallback } from "react";
import { useParams, useSearchParams, useRouter } from "next/navigation";
import { usePipelineStore } from "@/lib/store/pipeline-store";
import {
  Images,
  Plus,
  Search,
  Loader2,
  Trash2,
  X,
  Sparkles,
} from "lucide-react";
import { motion } from "framer-motion";
import { cn } from "@/lib/utils";
import { assetApi, type Asset } from "@/lib/api/asset";
import { resolveMediaUrl } from "@/lib/api/client";
import AssetDetailPanel from "@/components/dashboard/asset-detail-sheet";
import { SafeImage } from "@/components/ui/safe-image";
import AssetTypePlaceholder from "@/components/dashboard/asset-type-placeholder";
import { useFullWidth } from "@/lib/hooks/use-layout";

const containerVariants = {
  hidden: { opacity: 0 },
  visible: {
    opacity: 1,
    transition: { staggerChildren: 0.06, delayChildren: 0.1 },
  },
};
const itemVariants = {
  hidden: { opacity: 0, y: 16 },
  visible: {
    opacity: 1,
    y: 0,
    transition: { duration: 0.4, ease: [0.25, 0.46, 0.45, 0.94] as [number, number, number, number] },
  },
};

// 资产类型配置
const assetTypes = [
  { key: undefined as string | undefined, label: "全部" },
  { key: "character", label: "角色" },
  { key: "scene", label: "场景" },
  { key: "prop", label: "道具" },
];

const typeColorMap: Record<string, string> = {
  character: "text-blue-400 bg-blue-500/10",
  scene: "text-green-400 bg-green-500/10",
  prop: "text-amber-400 bg-amber-500/10",
};

const typeLabelMap: Record<string, string> = {
  character: "角色",
  scene: "场景",
  prop: "道具",
};

const emptyImageSummary = {
  totalItems: 0,
  pendingItems: 0,
  pendingAssets: 0,
  loading: true,
  loaded: false,
};

export default function ProjectAssetsPage() {
  const params = useParams();
  const searchParams = useSearchParams();
  const router = useRouter();
  const projectId = Number(params.id);
  const highlightId = searchParams.get("highlight");

  const [assets, setAssets] = useState<Asset[]>([]);
  const [loading, setLoading] = useState(true);
  const [imageSummary, setImageSummary] = useState(emptyImageSummary);
  const [startingBatchGen, setStartingBatchGen] = useState(false);
  const [activeType, setActiveType] = useState<string | undefined>(undefined);
  const [search, setSearch] = useState("");
  const debounceRef = useRef<ReturnType<typeof setTimeout>>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  const addPipeline = usePipelineStore((s) => s.addPipeline);
  const setNotificationOpen = usePipelineStore((s) => s.setNotificationOpen);
  const hasRunningBatchImageTask = usePipelineStore((s) =>
    s.tasks.some(
      (task) =>
        task.projectId === projectId &&
        task.status === "running" &&
        task.label.startsWith("资产一键生图")
    )
  );

  // 选中资产
  const [selectedAsset, setSelectedAsset] = useState<Asset | null>(null);
  // 创建模式
  const [isCreating, setIsCreating] = useState(false);

  const isPanelOpen = !!selectedAsset || isCreating;

  // 展开编辑面板时占满 layout 宽度
  useFullWidth(isPanelOpen);

  const fetchData = useCallback(async (type?: string, keyword?: string) => {
    try {
      setLoading(true);
      const data = await assetApi.list(projectId, type, keyword || undefined);
      setAssets(data);
    } catch (err) {
      console.error("加载资产列表失败:", err);
    } finally {
      setLoading(false);
    }
  }, [projectId]);

  const fetchImageSummary = useCallback(async () => {
    setImageSummary((prev) => ({ ...prev, loading: true }));
    try {
      const list = await assetApi.listWithItems(projectId);
      let totalItems = 0;
      let pendingItems = 0;
      const pendingAssetIds = new Set<number>();

      for (const asset of list) {
        for (const item of asset.items ?? []) {
          totalItems += 1;
          if (!item.imageUrl?.trim()) {
            pendingItems += 1;
            pendingAssetIds.add(asset.id);
          }
        }
      }

      setImageSummary({
        totalItems,
        pendingItems,
        pendingAssets: pendingAssetIds.size,
        loading: false,
        loaded: true,
      });
    } catch (err) {
      console.error("统计待生图资产失败:", err);
      setImageSummary((prev) => ({ ...prev, loading: false, loaded: false }));
    }
  }, [projectId]);

  useEffect(() => {
    if (debounceRef.current) clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(() => {
      fetchData(activeType, search);
    }, search ? 300 : 0);
    return () => { if (debounceRef.current) clearTimeout(debounceRef.current); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId, activeType, search]);

  useEffect(() => {
    fetchImageSummary();
  }, [fetchImageSummary]);

  // AI 工具执行后自动刷新
  const assetsInvalidation = usePipelineStore((s) => s.invalidation.assets);
  const assetsInvRef = useRef(assetsInvalidation);
  useEffect(() => {
    if (assetsInvRef.current !== assetsInvalidation) {
      assetsInvRef.current = assetsInvalidation;
      fetchData(activeType, search);
      fetchImageSummary();
    }
  }, [assetsInvalidation, activeType, search, fetchData, fetchImageSummary]);

  // URL highlight 参数驱动自动选中
  const highlightHandled = useRef(false);
  useEffect(() => {
    if (!highlightId || highlightHandled.current || loading || assets.length === 0) return;
    const target = assets.find((a) => String(a.id) === highlightId);
    if (target) {
      setSelectedAsset(target);
      highlightHandled.current = true;
      // 清除 URL 中的 highlight 参数，避免刷新重复触发
      router.replace(`/projects/${projectId}/assets`, { scroll: false });
    }
  }, [highlightId, loading, assets, router, projectId]);

  const handleDelete = async (id: number) => {
    if (!confirm("确定要删除该资产吗？")) return;
    try {
      await assetApi.delete(id);
      if (selectedAsset?.id === id) setSelectedAsset(null);
      await fetchData(activeType, search);
      await fetchImageSummary();
    } catch (err) {
      console.error("删除资产失败:", err);
    }
  };

  const handleBatchGenerateImages = () => {
    if (
      startingBatchGen ||
      hasRunningBatchImageTask ||
      imageSummary.loading ||
      !imageSummary.loaded ||
      imageSummary.pendingItems === 0
    ) {
      return;
    }

    setStartingBatchGen(true);
    addPipeline({
      label: `资产一键生图 (${imageSummary.pendingItems} 张)`,
      projectId,
      request: {
        agentType: "asset_image_gen",
        category: "asset_image_batch",
        title: "资产一键生图",
        projectId,
        enableParallelTools: true,
        message: "请为当前项目资产中所有尚未生成图片的子资产批量生成图片，已有图片不要覆盖。",
        context: {
          batchMode: "missing_project_asset_images",
          onlyMissingImages: true,
          pendingItemCount: imageSummary.pendingItems,
        },
      },
      onComplete: () => {
        fetchData(activeType, search);
        fetchImageSummary();
      },
    });
    setNotificationOpen(true);
    setStartingBatchGen(false);
  };

  const handleOpenCreate = () => {
    setSelectedAsset(null);
    setIsCreating(true);
  };

  // 选中资产时自动滚动
  useEffect(() => {
    if (!selectedAsset || !scrollRef.current) return;
    const timer = setTimeout(() => {
      const container = scrollRef.current;
      const el = container?.querySelector(`[data-asset-id="${selectedAsset.id}"]`) as HTMLElement | null;
      if (!container || !el) return;
      const containerRect = container.getBoundingClientRect();
      const elRect = el.getBoundingClientRect();
      // 如果元素不在可视区域内，滚动到居中位置
      if (elRect.top < containerRect.top || elRect.bottom > containerRect.bottom) {
        const scrollTarget = el.offsetTop - container.offsetTop - containerRect.height / 2 + elRect.height / 2;
        container.scrollTo({ top: scrollTarget, behavior: "smooth" });
      }
    }, 250);
    return () => clearTimeout(timer);
  }, [selectedAsset]);

  return (
    <motion.div variants={containerVariants} initial="hidden" animate="visible" className="h-full relative overflow-hidden">
      {/* ========== 左侧：资产列表（宽度立即跳到目标值，卡片用 layout 动画归位） ========== */}
      <div
        className="h-full flex flex-col min-h-0 overflow-hidden"
        style={{ width: isPanelOpen ? "40%" : "100%" }}
      >
        {/* 标题 + 操作 */}
        <motion.div variants={itemVariants} className="flex items-center justify-between mb-4 shrink-0 px-1">
          <h2 className="text-xl font-semibold flex items-center gap-2">
            <Images className="h-5 w-5 text-primary" />
            资产库
            <span className="text-xs text-muted-foreground font-normal ml-1">
              {assets.length} 个
            </span>
          </h2>
          <div className="flex items-center gap-2">
            <span
              className={cn(
                "hidden sm:flex items-center gap-1.5 rounded-lg border border-border/25 px-2.5 py-1.5",
                "bg-card/45 text-xs text-muted-foreground"
              )}
            >
              {imageSummary.loading ? (
                <>
                  <Loader2 className="h-3.5 w-3.5 animate-spin" />
                  统计中
                </>
              ) : (
                <>待生图 {imageSummary.pendingItems} 张</>
              )}
            </span>
            <button
              onClick={handleBatchGenerateImages}
              disabled={
                startingBatchGen ||
                hasRunningBatchImageTask ||
                imageSummary.loading ||
                !imageSummary.loaded ||
                imageSummary.pendingItems === 0
              }
              title={
                imageSummary.pendingItems > 0
                  ? `生成 ${imageSummary.pendingAssets} 个资产下的 ${imageSummary.pendingItems} 张缺失图片`
                  : "当前没有待生成图片"
              }
              className={cn(
                "flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-sm font-medium transition-all",
                "border border-purple-500/20 bg-purple-500/10 text-purple-300 hover:bg-purple-500/15",
                "disabled:opacity-45 disabled:pointer-events-none"
              )}
            >
              {startingBatchGen || hasRunningBatchImageTask ? (
                <Loader2 className="h-3.5 w-3.5 animate-spin" />
              ) : (
                <Sparkles className="h-3.5 w-3.5" />
              )}
              一键生图
            </button>
            <button
              onClick={handleOpenCreate}
              className={cn(
                "flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-sm font-medium transition-all",
                "bg-primary text-primary-foreground hover:bg-primary/90"
              )}
            >
              <Plus className="h-3.5 w-3.5" />
              新建
            </button>
          </div>
        </motion.div>

        {/* 类型过滤标签 + 搜索 */}
        <motion.div variants={itemVariants} className="flex items-center gap-3 mb-4 shrink-0 px-1">
          <div className="flex items-center gap-1 overflow-x-auto flex-1">
            {assetTypes.map((t) => (
              <button
                key={t.key ?? "all"}
                onClick={() => setActiveType(t.key)}
                className={cn(
                  "px-2.5 py-1 rounded-lg text-xs font-medium transition-all whitespace-nowrap",
                  activeType === t.key
                    ? "bg-foreground/10 text-foreground"
                    : "text-muted-foreground hover:text-foreground hover:bg-foreground/5"
                )}
              >
                {t.label}
              </button>
            ))}
          </div>
          <div className="relative w-48 shrink-0 group/search">
            <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-muted-foreground/40 group-focus-within/search:text-primary/60 transition-colors" />
            <input
              type="text"
              placeholder="搜索资产..."
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              className="w-full pl-8 pr-7 py-1.5 rounded-lg border border-border/30 bg-card/50 text-xs outline-none placeholder:text-muted-foreground/40 focus:border-primary/40 focus:ring-1 focus:ring-primary/20 transition-all"
            />
            {search && (
              <button
                onClick={() => setSearch("")}
                className="absolute right-2 top-1/2 -translate-y-1/2 p-0.5 rounded text-muted-foreground/40 hover:text-foreground transition-colors"
              >
                <X className="h-3 w-3" />
              </button>
            )}
          </div>
        </motion.div>

        {/* 资产网格 */}
        <motion.div variants={itemVariants} ref={scrollRef} className="flex-1 min-h-0 overflow-y-auto px-1">
          {loading ? (
            <div className="flex items-center justify-center py-20">
              <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
            </div>
          ) : assets.length === 0 ? (
            <div className="rounded-xl border border-dashed border-border/30 p-10 flex flex-col items-center justify-center text-center bg-card/10">
              <Images className="h-8 w-8 text-muted-foreground/20 mb-2" />
              <p className="text-muted-foreground/60 text-xs">
                {search ? "没有找到匹配的资产" : "暂无资产"}
              </p>
            </div>
          ) : (
            <motion.div
              className="grid gap-3 pb-4 p-1"
              style={{
                gridTemplateColumns: "repeat(auto-fill, minmax(300px, 1fr))",
              }}
            >
              {assets.map((asset) => {
                const color = typeColorMap[asset.type] || "text-muted-foreground bg-muted/50";
                const isSelected = selectedAsset?.id === asset.id;
                return (
                  <motion.div
                    key={asset.id}
                    layout
                    layoutId={`asset-card-${asset.id}`}
                    transition={{ type: "spring", stiffness: 350, damping: 30 }}
                    data-asset-id={asset.id}
                    className={cn(
                      "group relative rounded-xl border cursor-pointer overflow-hidden",
                      "bg-card/50 transition-colors duration-150",
                      isSelected
                        ? "border-primary/40 ring-1 ring-primary/20"
                        : "border-border/25 hover:border-border/50"
                    )}
                    onClick={() => { setIsCreating(false); setSelectedAsset(asset); }}
                  >
                    {/* 封面 */}
                    <div className="aspect-4/3 relative overflow-hidden">
                      {asset.coverUrl ? (
                        <>
                          {/* 毛玻璃背景层：用同一张图放大模糊填充留白区域 */}
                          <SafeImage
                            src={resolveMediaUrl(asset.coverUrl) || undefined}
                            alt=""
                            aria-hidden
                            className="absolute inset-0 w-full h-full object-cover scale-110 blur-xl opacity-60"
                            fallback={<div className="hidden" />}
                          />
                          {/* 前景图：object-contain 完整显示 */}
                          <SafeImage
                            src={resolveMediaUrl(asset.coverUrl) || undefined}
                            alt={asset.name}
                            className="relative w-full h-full object-contain z-1"
                            fallbackType={
                              asset.type === "character"
                                ? "avatar"
                                : asset.type === "scene"
                                ? "scene"
                                : asset.type === "prop"
                                ? "prop"
                                : "image"
                            }
                          />
                        </>
                      ) : (
                        <AssetTypePlaceholder type={asset.type} className="w-full h-full" />
                      )}
                      {/* 删除按钮 hover 显示 */}
                      <button
                        onClick={(e) => { e.stopPropagation(); handleDelete(asset.id); }}
                        className="absolute top-1.5 right-1.5 p-1 rounded-md bg-black/40 text-white/70 hover:bg-destructive hover:text-white opacity-0 group-hover:opacity-100 transition-all backdrop-blur-sm z-20"
                      >
                        <Trash2 className="h-3 w-3" />
                      </button>
                    </div>
                    {/* 信息 */}
                    <div className="px-2.5 py-2">
                      <p className="text-xs font-medium truncate mb-0.5">{asset.name}</p>
                      <div className="flex items-center gap-1.5">
                        <span className={cn("px-1 py-0.5 rounded text-[9px] leading-none shrink-0 whitespace-nowrap", color)}>
                          {typeLabelMap[asset.type] || asset.type}
                        </span>
                        {asset.description && (
                          <span className="text-[10px] text-muted-foreground/50 truncate min-w-0">{asset.description}</span>
                        )}
                      </div>
                    </div>
                  </motion.div>
                );
              })}
            </motion.div>
          )}
        </motion.div>
      </div>

      {/* ========== 右侧：详情面板（absolute 定位，从右侧滑入） ========== */}
      <div
        className={cn(
          "absolute top-0 right-0 h-full min-h-0 overflow-hidden border-l border-border/20 bg-card/20",
          "transition-all duration-400 ease-in-out"
        )}
        style={{
          width: isPanelOpen ? "60%" : "0%",
          opacity: isPanelOpen ? 1 : 0,
          borderLeftWidth: isPanelOpen ? undefined : 0,
        }}
      >
        {isCreating ? (
          <AssetDetailPanel
            key="create"
            isCreating
            projectId={projectId}
            onClose={() => setIsCreating(false)}
            onSaved={async () => {
              await fetchData(activeType);
              await fetchImageSummary();
            }}
            onCreated={async (created) => {
              setIsCreating(false);
              await fetchData(activeType);
              await fetchImageSummary();
              setSelectedAsset(created);
            }}
          />
        ) : selectedAsset ? (
          <AssetDetailPanel
            key={selectedAsset.id}
            asset={selectedAsset}
            onClose={() => setSelectedAsset(null)}
            onSaved={() => {
              fetchData(activeType);
              fetchImageSummary();
            }}
            onDeleted={() => {
              setSelectedAsset(null);
              fetchData(activeType);
              fetchImageSummary();
            }}
          />
        ) : null}
      </div>
    </motion.div>
  );
}
