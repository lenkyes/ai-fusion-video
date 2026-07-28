"use client";

import {
  Background,
  BackgroundVariant,
  ConnectionLineType,
  Handle,
  MarkerType,
  MiniMap,
  NodeResizer,
  Position,
  ReactFlow,
  ReactFlowProvider,
  SelectionMode,
  addEdge,
  applyEdgeChanges,
  applyNodeChanges,
  useReactFlow,
  type Connection,
  type Edge,
  type EdgeChange,
  type FinalConnectionState,
  type Node,
  type NodeChange,
  type NodeProps,
  type ReactFlowInstance,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import {
  Check,
  CircleAlert,
  Copy,
  Download,
  FilePlus2,
  FileUp,
  Focus,
  ImageIcon,
  Link2,
  Loader2,
  MousePointer2,
  Network,
  StickyNote,
  Plus,
  Redo2,
  RotateCcw,
  Save,
  Sparkles,
  Trash2,
  Type,
  Undo2,
  Video,
  X,
  ZoomIn,
  ZoomOut,
} from "lucide-react";
import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type ChangeEvent,
  type ReactNode,
} from "react";
import { createPortal } from "react-dom";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { aiModelApi, type AiModel } from "@/lib/api/ai-model";
import { pipelineStream } from "@/lib/api/ai-pipeline";
import { imageGenerationApi } from "@/lib/api/image-generation";
import { videoGenerationApi, type VideoModelCapability } from "@/lib/api/video-generation";
import { resolveMediaUrl } from "@/lib/api/client";
import { uploadFile, uploadVideo } from "@/lib/api/storage";

type CanvasNodeKind = "text" | "note" | "image" | "video";

type CanvasNodeData = {
  kind: CanvasNodeKind;
  title: string;
  content?: string;
  url?: string;
  status?: "idle" | "queued" | "running" | "done" | "error";
  error?: string;
  modelName?: string;
  generated?: boolean;
  uploading?: boolean;
};

type CanvasNode = Node<CanvasNodeData, "canvasNode">;

type CanvasDocument = {
  version: 1 | 2;
  nodes: CanvasNode[];
  edges: Edge[];
  viewport: { x: number; y: number; zoom: number };
};

type HistoryEntry = Pick<CanvasDocument, "nodes" | "edges">;

type QuickCreateState = {
  sourceId: string;
  flowPosition: { x: number; y: number };
  screenPosition: { x: number; y: number };
};

type MediaPreview = {
  kind: "image" | "video";
  title: string;
  url: string;
};

const STORAGE_KEY = "ai-fusion-infinite-canvas-v2";
const nodeColors: Record<CanvasNodeKind, string> = {
  text: "#10b981",
  note: "#f59e0b",
  image: "#0ea5e9",
  video: "#ec4899",
};

const initialNodes: CanvasNode[] = [
  {
    id: "welcome-prompt",
    type: "canvasNode",
    dragHandle: ".drag-handle",
    style: { width: 300, height: 250 },
    position: { x: 40, y: 80 },
    data: {
      kind: "text",
      title: "创意起点",
      content: "双击文字开始编辑，把提示词、参考图和视频用连线组织起来。",
    },
  },
];

const initialEdges: Edge[] = [];

function cloneHistory(nodes: CanvasNode[], edges: Edge[]): HistoryEntry {
  return {
    nodes: structuredClone(nodes),
    edges: structuredClone(edges),
  };
}

function wait(ms: number) {
  return new Promise((resolve) => window.setTimeout(resolve, ms));
}

function CanvasNodeView({ id, data, selected }: NodeProps<CanvasNode>) {
  const icon = {
    text: <Type />,
    note: <StickyNote />,
    image: <ImageIcon />,
    video: <Video />,
  }[data.kind];

  const updateData = (patch: Partial<CanvasNodeData>) => {
    window.dispatchEvent(
      new CustomEvent("infinite-canvas:update-node", { detail: { id, patch } }),
    );
  };

  const runGeneration = () => {
    window.dispatchEvent(
      new CustomEvent("infinite-canvas:run-node", { detail: { id } }),
    );
  };

  const openPreview = () => {
    if (!data.url || (data.kind !== "image" && data.kind !== "video")) return;
    window.dispatchEvent(
      new CustomEvent("infinite-canvas:preview-media", {
        detail: { kind: data.kind, title: data.title, url: data.url },
      }),
    );
  };

  return (
    <article
      className={cn(
        "group flex size-full min-h-[190px] min-w-[240px] flex-col overflow-hidden rounded-lg border bg-card shadow-sm transition-shadow",
        selected ? "border-emerald-500 shadow-lg shadow-emerald-500/10" : "border-border/70",
      )}
    >
      <NodeResizer
        isVisible={selected}
        minWidth={240}
        minHeight={data.kind === "text" || data.kind === "note" ? 190 : 230}
        maxWidth={1000}
        maxHeight={900}
        lineClassName="!border-emerald-500"
        handleClassName="!size-3 !rounded-sm !border-2 !border-background !bg-emerald-500"
      />
      <Handle type="target" position={Position.Left} className="!size-3 !border-2 !border-background !bg-emerald-500" />
      <header className="drag-handle flex h-10 cursor-grab items-center gap-2 border-b bg-muted/30 px-3 active:cursor-grabbing">
        <span className="text-emerald-500 [&_svg]:size-4">{icon}</span>
        <input
          value={data.title}
          onChange={(event) => updateData({ title: event.target.value })}
          className="nodrag min-w-0 flex-1 bg-transparent text-sm font-medium outline-none"
          aria-label="节点标题"
        />
        <Link2 className="size-3.5 text-muted-foreground opacity-0 transition-opacity group-hover:opacity-100" />
      </header>

      {data.kind === "image" && data.url ? (
        <div className="nodrag relative min-h-0 flex-1 cursor-zoom-in bg-muted/40" onDoubleClick={openPreview} title="双击全屏查看">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={resolveMediaUrl(data.url) ?? data.url} alt={data.title} className="h-full w-full object-contain" />
        </div>
      ) : null}

      {data.kind === "image" && !data.url ? (
        <div className="flex min-h-28 flex-1 flex-col items-center justify-center gap-2 bg-muted/25 text-muted-foreground">
          <ImageIcon className="size-7" />
          <span className="text-xs">连接文本后点击生成</span>
        </div>
      ) : null}

      {data.kind === "video" && data.url ? (
        <div className="nodrag nowheel min-h-0 flex-1 cursor-zoom-in bg-black" onDoubleClick={openPreview} title="双击全屏查看">
          <video src={resolveMediaUrl(data.url) ?? data.url} controls className="h-full w-full object-contain" />
        </div>
      ) : null}

      {data.kind === "video" && !data.url ? (
        <div className="flex min-h-28 flex-1 flex-col items-center justify-center gap-2 bg-black/5 text-muted-foreground dark:bg-white/5">
          <Video className="size-7" />
          <span className="text-xs">连接文本或图片后点击生成</span>
        </div>
      ) : null}

      {(data.kind === "text" || data.kind === "note") && (
        <textarea
          value={data.content ?? ""}
          onChange={(event) => updateData({ content: event.target.value })}
          placeholder={data.kind === "text" ? "输入提示词或文本..." : "记录创意和待办..."}
          className={cn(
            "nodrag nowheel min-h-0 w-full flex-1 resize-none bg-transparent p-3 text-sm leading-6 outline-none",
            data.kind === "note" && "bg-amber-500/5",
          )}
        />
      )}

      {data.kind !== "note" ? (
        <footer className="nodrag flex items-center justify-between gap-2 border-t bg-muted/15 px-3 py-2">
          <span className="min-w-0 truncate text-[10px] text-muted-foreground">
            {data.status === "error" ? (
              <span className="flex items-center gap-1 text-destructive"><CircleAlert className="size-3" />{data.error || "生成失败"}</span>
            ) : data.modelName ? `默认模型 · ${data.modelName}` : "正在读取默认模型"}
          </span>
          <Button
            size="xs"
            onClick={runGeneration}
            disabled={data.status === "queued" || data.status === "running" || !data.modelName}
          >
            {data.status === "queued" || data.status === "running" ? <Loader2 className="animate-spin" /> : <Sparkles />}
            {data.uploading ? "上传中" : data.status === "queued" ? "排队中" : data.status === "running" ? "生成中" : data.generated ? "重新生成" : "生成"}
          </Button>
        </footer>
      ) : null}

      <Handle type="source" position={Position.Right} className="!size-3 !border-2 !border-background !bg-emerald-500" />
    </article>
  );
}

const nodeTypes = { canvasNode: CanvasNodeView };

function ToolButton({
  icon,
  label,
  onClick,
}: {
  icon: ReactNode;
  label: string;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="flex h-16 w-16 flex-col items-center justify-center gap-1 rounded-md text-[11px] text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
      title={label}
    >
      <span className="[&_svg]:size-5">{icon}</span>
      {label}
    </button>
  );
}

function MediaPreviewOverlay({ preview, onClose }: { preview: MediaPreview; onClose: () => void }) {
  useEffect(() => {
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose();
    };
    window.addEventListener("keydown", closeOnEscape);
    return () => window.removeEventListener("keydown", closeOnEscape);
  }, [onClose]);

  const source = resolveMediaUrl(preview.url) ?? preview.url;
  return createPortal(
    <div className="fixed inset-0 z-200 flex flex-col bg-black/92 backdrop-blur-sm" onClick={onClose}>
      <header className="flex h-14 shrink-0 items-center justify-between border-b border-white/10 px-4 text-white">
        <h2 className="truncate text-sm font-medium">{preview.title}</h2>
        <button type="button" onClick={onClose} className="rounded-md p-2 text-white/80 hover:bg-white/10 hover:text-white" aria-label="关闭预览">
          <X className="size-5" />
        </button>
      </header>
      <div className="flex min-h-0 flex-1 items-center justify-center p-4" onClick={(event) => event.stopPropagation()}>
        {preview.kind === "image" ? (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={source} alt={preview.title} className="max-h-full max-w-full object-contain" />
        ) : (
          <video src={source} controls autoPlay playsInline className="max-h-full max-w-full object-contain" />
        )}
      </div>
    </div>,
    document.body,
  );
}

function CanvasWorkspace() {
  const [nodes, setNodes] = useState<CanvasNode[]>(initialNodes);
  const [edges, setEdges] = useState<Edge[]>(initialEdges);
  const [instance, setInstance] = useState<ReactFlowInstance<CanvasNode, Edge> | null>(null);
  const [hydrated, setHydrated] = useState(false);
  const [saved, setSaved] = useState(true);
  const [past, setPast] = useState<HistoryEntry[]>([]);
  const [future, setFuture] = useState<HistoryEntry[]>([]);
  const [dragActive, setDragActive] = useState(false);
  const [quickCreate, setQuickCreate] = useState<QuickCreateState | null>(null);
  const [mediaPreview, setMediaPreview] = useState<MediaPreview | null>(null);
  const [models, setModels] = useState<Record<1 | 2 | 3, AiModel | null>>({ 1: null, 2: null, 3: null });
  const [videoCapability, setVideoCapability] = useState<VideoModelCapability | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const importInputRef = useRef<HTMLInputElement>(null);
  const historyRef = useRef<HistoryEntry>(cloneHistory(initialNodes, initialEdges));
  const draggingRef = useRef(false);
  const didHydrateRef = useRef(false);
  const nodesRef = useRef(nodes);
  const edgesRef = useRef(edges);
  const activeStreamsRef = useRef(new Map<string, AbortController>());
  const { screenToFlowPosition, zoomIn, zoomOut, fitView } = useReactFlow<CanvasNode, Edge>();

  const pushHistory = useCallback(() => {
    setPast((entries) => [...entries.slice(-49), historyRef.current]);
    setFuture([]);
  }, []);

  const commitState = useCallback((nextNodes: CanvasNode[], nextEdges: Edge[]) => {
    historyRef.current = cloneHistory(nextNodes, nextEdges);
    setSaved(false);
  }, []);

  useEffect(() => {
    nodesRef.current = nodes;
  }, [nodes]);

  useEffect(() => {
    edgesRef.current = edges;
  }, [edges]);

  const patchNode = useCallback((id: string, patch: Partial<CanvasNodeData>) => {
    setNodes((current) => {
      const next = current.map((node) =>
        node.id === id ? { ...node, data: { ...node.data, ...patch } } : node,
      );
      nodesRef.current = next;
      commitState(next, edgesRef.current);
      return next;
    });
  }, [commitState]);

  useEffect(() => {
    let cancelled = false;
    const activeStreams = activeStreamsRef.current;
    Promise.all([1, 2, 3].map((type) => aiModelApi.listByType(type)))
      .then(async ([textModels, imageModels, videoModels]) => {
        if (cancelled) return;
        const nextModels = {
          1: textModels.find((model) => model.defaultModel) ?? textModels[0] ?? null,
          2: imageModels.find((model) => model.defaultModel) ?? imageModels[0] ?? null,
          3: videoModels.find((model) => model.defaultModel) ?? videoModels[0] ?? null,
        } as Record<1 | 2 | 3, AiModel | null>;
        setModels(nextModels);
        if (nextModels[3]) {
          setVideoCapability(await videoGenerationApi.capability(nextModels[3].id));
        }
      })
      .catch((error) => toast.error(error instanceof Error ? error.message : "默认模型加载失败"));
    return () => {
      cancelled = true;
      activeStreams.forEach((controller) => controller.abort());
    };
  }, []);

  useEffect(() => {
    const names: Record<CanvasNodeKind, string | undefined> = {
      text: models[1]?.name,
      note: undefined,
      image: models[2]?.name,
      video: models[3]?.name,
    };
    setNodes((current) => {
      const next = current.map((node) => ({
        ...node,
        data: { ...node.data, modelName: names[node.data.kind] },
      }));
      nodesRef.current = next;
      return next;
    });
  }, [models]);

  const addNode = useCallback(
    (kind: CanvasNodeKind, url?: string, position?: { x: number; y: number }, title?: string) => {
      pushHistory();
      const id = crypto.randomUUID();
      const nextNode: CanvasNode = {
        id,
        type: "canvasNode",
        dragHandle: ".drag-handle",
        style: { width: 300, height: kind === "text" || kind === "note" ? 250 : 280 },
        position: position ?? screenToFlowPosition({ x: window.innerWidth / 2, y: window.innerHeight / 2 }),
        data: {
          kind,
          title: title ?? ({ text: "文本", note: "便签", image: "图片", video: "视频" }[kind]),
          content: kind === "text" || kind === "note" ? "" : undefined,
          url,
          status: "idle",
          modelName: kind === "text" ? models[1]?.name : kind === "image" ? models[2]?.name : kind === "video" ? models[3]?.name : undefined,
        },
      };
      setNodes((current) => {
        const next = [...current.map((node) => ({ ...node, selected: false })), { ...nextNode, selected: true }];
        commitState(next, edges);
        return next;
      });
      return id;
    },
    [commitState, edges, models, pushHistory, screenToFlowPosition],
  );

  useEffect(() => {
    if (!instance || didHydrateRef.current) return;
    didHydrateRef.current = true;
    try {
      const savedDocument = localStorage.getItem(STORAGE_KEY);
      if (savedDocument) {
        const document = JSON.parse(savedDocument) as CanvasDocument;
        if ((document.version === 1 || document.version === 2) && Array.isArray(document.nodes) && Array.isArray(document.edges)) {
          setNodes(document.nodes);
          setEdges(document.edges);
          historyRef.current = cloneHistory(document.nodes, document.edges);
          requestAnimationFrame(() => instance.setViewport(document.viewport));
        }
      }
    } catch {
      toast.error("画布草稿读取失败，已使用空白画布");
    } finally {
      setHydrated(true);
    }
  }, [instance]);

  useEffect(() => {
    if (!hydrated) return;
    const timer = window.setTimeout(() => {
      const document: CanvasDocument = {
        version: 2,
        nodes,
        edges,
        viewport: instance?.getViewport() ?? { x: 0, y: 0, zoom: 1 },
      };
      localStorage.setItem(STORAGE_KEY, JSON.stringify(document));
      setSaved(true);
    }, 500);
    return () => window.clearTimeout(timer);
  }, [edges, hydrated, instance, nodes]);

  useEffect(() => {
    const updateNode = (event: Event) => {
      const { id, patch } = (event as CustomEvent<{ id: string; patch: Partial<CanvasNodeData> }>).detail;
      patchNode(id, patch);
    };
    window.addEventListener("infinite-canvas:update-node", updateNode);
    return () => window.removeEventListener("infinite-canvas:update-node", updateNode);
  }, [patchNode]);

  const collectInputs = useCallback((targetId: string) => {
    const currentNodes = nodesRef.current;
    const currentEdges = edgesRef.current;
    const byId = new Map(currentNodes.map((node) => [node.id, node]));
    const visited = new Set<string>();
    const upstream: CanvasNode[] = [];

    const visit = (id: string) => {
      currentEdges.filter((edge) => edge.target === id).forEach((edge) => {
        if (visited.has(edge.source)) return;
        visited.add(edge.source);
        visit(edge.source);
        const source = byId.get(edge.source);
        if (source) upstream.push(source);
      });
    };
    visit(targetId);

    return {
      texts: upstream
        .filter((node) => node.data.kind === "text" || node.data.kind === "note")
        .map((node) => node.data.content?.trim())
        .filter((value): value is string => Boolean(value)),
      images: upstream
        .filter((node) => node.data.kind === "image" && node.data.url)
        .map((node) => node.data.url!)
        .filter((url) => !url.startsWith("blob:")),
    };
  }, []);

  const runTextNode = useCallback((node: CanvasNode, prompt: string) => {
    const model = models[1];
    if (!model) throw new Error("未配置默认文本模型");
    patchNode(node.id, { status: "running", error: undefined, content: "", generated: false });
    let output = "";
    let streamFailed = false;
    const controller = pipelineStream(
      {
        message: prompt,
        modelId: model.id,
        agentType: "ai_media",
        category: "infinite_canvas",
        title: prompt.slice(0, 40),
        context: { entry: "infinite_canvas", projectScope: "global_workspace" },
      },
      {
        onEvent: (event) => {
          if (event.outputType === "CONTENT" && event.content) {
            output += event.content;
            patchNode(node.id, { content: output, status: "running" });
          } else if (event.outputType === "ERROR") {
            streamFailed = true;
            patchNode(node.id, { status: "error", error: event.error || "文本生成失败" });
          } else if (event.outputType === "DONE") {
            patchNode(node.id, { status: "done", generated: true });
          }
        },
        onError: (error) => {
          streamFailed = true;
          activeStreamsRef.current.delete(node.id);
          patchNode(node.id, { status: "error", error: error.message });
        },
        onComplete: () => {
          activeStreamsRef.current.delete(node.id);
          if (!streamFailed) patchNode(node.id, { status: "done", generated: true });
        },
      },
    );
    activeStreamsRef.current.set(node.id, controller);
  }, [models, patchNode]);

  const runImageNode = useCallback(async (node: CanvasNode, prompt: string, references: string[]) => {
    const model = models[2];
    if (!model) throw new Error("未配置默认图片模型");
    patchNode(node.id, { status: "queued", error: undefined, generated: false });
    const taskId = await imageGenerationApi.submit({
      prompt,
      modelId: model.id,
      refImageUrls: references.length ? JSON.stringify(references) : undefined,
      count: 1,
      category: "infinite_canvas",
    });
    for (let index = 0; index < 180; index++) {
      const task = await imageGenerationApi.get(taskId);
      patchNode(node.id, { status: task.status === 0 ? "queued" : "running" });
      if (task.status === 3) throw new Error(task.errorMsg || "图片生成失败");
      if (task.status === 2) {
        const items = await imageGenerationApi.items(task.id);
        const result = items.find((item) => item.status === 1 && item.imageUrl);
        if (!result?.imageUrl) throw new Error("图片任务完成但没有返回结果");
        patchNode(node.id, { url: result.imageUrl, status: "done", generated: true });
        return;
      }
      await wait(2000);
    }
    throw new Error("图片生成超时");
  }, [models, patchNode]);

  const runVideoNode = useCallback(async (node: CanvasNode, prompt: string, references: string[]) => {
    const model = models[3];
    if (!model) throw new Error("未配置默认视频模型");
    const capability = videoCapability ?? await videoGenerationApi.capability(model.id);
    const maxImages = capability.maxImageInputs ?? references.length;
    const usableReferences = maxImages > 0 ? references.slice(0, maxImages) : [];
    const request: Record<string, unknown> = {
      prompt,
      modelId: model.id,
      generateMode: usableReferences.length ? "image2video" : "text2video",
      duration: capability.defaultDuration ?? 5,
      resolution: capability.supportedResolutions?.[0],
      ratio: capability.supportedAspectRatios?.[0],
      count: 1,
      category: "infinite_canvas",
    };
    if (usableReferences.length) {
      if (capability.supportsReferenceImages) {
        request.referenceImageUrls = JSON.stringify(usableReferences.slice(0, capability.maxReferenceImages ?? usableReferences.length));
      } else if (capability.supportsFirstFrame) {
        request.firstFrameImageUrl = usableReferences[0];
        if (capability.supportsLastFrame && usableReferences[1]) request.lastFrameImageUrl = usableReferences[1];
      }
    }
    patchNode(node.id, { status: "queued", error: undefined, generated: false });
    const taskId = await videoGenerationApi.submit(request);
    for (let index = 0; index < 300; index++) {
      const task = await videoGenerationApi.get(taskId);
      patchNode(node.id, { status: task.status === 0 ? "queued" : "running" });
      if (task.status === 3) throw new Error(task.errorMsg || "视频生成失败");
      if (task.status === 2) {
        const items = await videoGenerationApi.items(task.id);
        const result = items.find((item) => item.status === 1 && item.videoUrl);
        if (!result?.videoUrl) throw new Error("视频任务完成但没有返回结果");
        patchNode(node.id, { url: result.videoUrl, status: "done", generated: true });
        return;
      }
      await wait(3000);
    }
    throw new Error("视频生成超时");
  }, [models, patchNode, videoCapability]);

  useEffect(() => {
    const runNode = (event: Event) => {
      const { id } = (event as CustomEvent<{ id: string }>).detail;
      const node = nodesRef.current.find((item) => item.id === id);
      if (!node || node.data.kind === "note") return;
      const input = collectInputs(id);
      const ownText = node.data.kind === "text" ? node.data.content?.trim() : undefined;
      const promptParts = [...input.texts, ownText].filter((value): value is string => Boolean(value));
      const prompt = Array.from(new Set(promptParts)).join("\n\n");
      if (!prompt) {
        patchNode(id, { status: "error", error: "请连接一个文本节点作为提示词" });
        return;
      }
      if (node.data.status === "running" || node.data.status === "queued") return;
      pushHistory();
      try {
        if (node.data.kind === "text") {
          runTextNode(node, prompt);
        } else {
          const task = node.data.kind === "image"
            ? runImageNode(node, prompt, input.images)
            : runVideoNode(node, prompt, input.images);
          void task.catch((error) => {
            patchNode(id, { status: "error", error: error instanceof Error ? error.message : "生成失败" });
          });
        }
      } catch (error) {
        patchNode(id, { status: "error", error: error instanceof Error ? error.message : "生成失败" });
      }
    };
    window.addEventListener("infinite-canvas:run-node", runNode);
    return () => window.removeEventListener("infinite-canvas:run-node", runNode);
  }, [collectInputs, patchNode, pushHistory, runImageNode, runTextNode, runVideoNode]);

  const onNodesChange = useCallback(
    (changes: NodeChange<CanvasNode>[]) => {
      const startsDrag = changes.some((change) => change.type === "position" && change.dragging === true);
      const endsDrag = changes.some((change) => change.type === "position" && change.dragging === false);
      if (startsDrag && !draggingRef.current) {
        draggingRef.current = true;
        pushHistory();
      }
      if (endsDrag) draggingRef.current = false;
      setNodes((current) => {
        const next = applyNodeChanges(changes, current);
        if (changes.some((change) => change.type !== "select")) commitState(next, edges);
        return next;
      });
    },
    [commitState, edges, pushHistory],
  );

  const onEdgesChange = useCallback(
    (changes: EdgeChange<Edge>[]) => {
      if (changes.some((change) => change.type === "remove")) pushHistory();
      setEdges((current) => {
        const next = applyEdgeChanges(changes, current);
        if (changes.some((change) => change.type !== "select")) commitState(nodes, next);
        return next;
      });
    },
    [commitState, nodes, pushHistory],
  );

  const onConnect = useCallback(
    (connection: Connection) => {
      pushHistory();
      setEdges((current) => {
        const next = addEdge(
          {
            ...connection,
            type: "smoothstep",
            markerEnd: { type: MarkerType.ArrowClosed },
          },
          current,
        );
        commitState(nodes, next);
        return next;
      });
    },
    [commitState, nodes, pushHistory],
  );

  const onConnectEnd = useCallback(
    (event: MouseEvent | TouchEvent, connectionState: FinalConnectionState) => {
      if (connectionState.isValid || connectionState.toNode || !connectionState.fromNode) return;
      const point = "changedTouches" in event ? event.changedTouches[0] : event;
      if (!point) return;
      setQuickCreate({
        sourceId: connectionState.fromNode.id,
        flowPosition: screenToFlowPosition({ x: point.clientX, y: point.clientY }),
        screenPosition: { x: point.clientX, y: point.clientY },
      });
    },
    [screenToFlowPosition],
  );

  const createConnectedNode = useCallback((kind: CanvasNodeKind) => {
    if (!quickCreate) return;
    const sourceId = quickCreate.sourceId;
    const nodeId = addNode(kind, undefined, quickCreate.flowPosition);
    setEdges((current) => {
      const next = addEdge(
        {
          id: crypto.randomUUID(),
          source: sourceId,
          target: nodeId,
          type: "smoothstep",
          markerEnd: { type: MarkerType.ArrowClosed },
        },
        current,
      );
      edgesRef.current = next;
      commitState(nodesRef.current, next);
      return next;
    });
    setQuickCreate(null);
  }, [addNode, commitState, quickCreate]);

  useEffect(() => {
    const openPreview = (event: Event) => {
      setMediaPreview((event as CustomEvent<MediaPreview>).detail);
    };
    window.addEventListener("infinite-canvas:preview-media", openPreview);
    return () => window.removeEventListener("infinite-canvas:preview-media", openPreview);
  }, []);

  const deleteSelected = useCallback(() => {
    const selectedIds = new Set(nodes.filter((node) => node.selected).map((node) => node.id));
    const hasSelection = selectedIds.size > 0 || edges.some((edge) => edge.selected);
    if (!hasSelection) return;
    pushHistory();
    const nextNodes = nodes.filter((node) => !selectedIds.has(node.id));
    const nextEdges = edges.filter(
      (edge) => !edge.selected && !selectedIds.has(edge.source) && !selectedIds.has(edge.target),
    );
    setNodes(nextNodes);
    setEdges(nextEdges);
    commitState(nextNodes, nextEdges);
  }, [commitState, edges, nodes, pushHistory]);

  const duplicateSelected = useCallback(() => {
    const selected = nodes.filter((node) => node.selected);
    if (!selected.length) return;
    pushHistory();
    const copies = selected.map((node) => ({
      ...node,
      id: crypto.randomUUID(),
      position: { x: node.position.x + 36, y: node.position.y + 36 },
      selected: true,
    }));
    const next = [...nodes.map((node) => ({ ...node, selected: false })), ...copies];
    setNodes(next);
    commitState(next, edges);
  }, [commitState, edges, nodes, pushHistory]);

  const undo = useCallback(() => {
    const previous = past.at(-1);
    if (!previous) return;
    setFuture((entries) => [cloneHistory(nodes, edges), ...entries].slice(0, 50));
    setPast((entries) => entries.slice(0, -1));
    setNodes(previous.nodes);
    setEdges(previous.edges);
    commitState(previous.nodes, previous.edges);
  }, [commitState, edges, nodes, past]);

  const redo = useCallback(() => {
    const next = future[0];
    if (!next) return;
    setPast((entries) => [...entries, cloneHistory(nodes, edges)].slice(-50));
    setFuture((entries) => entries.slice(1));
    setNodes(next.nodes);
    setEdges(next.edges);
    commitState(next.nodes, next.edges);
  }, [commitState, edges, future, nodes]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement;
      if (target.matches("input, textarea, [contenteditable='true']")) return;
      const mod = event.ctrlKey || event.metaKey;
      if (mod && event.key.toLowerCase() === "z") {
        event.preventDefault();
        if (event.shiftKey) redo();
        else undo();
      } else if (mod && event.key.toLowerCase() === "d") {
        event.preventDefault();
        duplicateSelected();
      } else if (event.key === "Delete" || event.key === "Backspace") {
        deleteSelected();
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [deleteSelected, duplicateSelected, redo, undo]);

  const handleMediaFiles = (files: FileList | File[], position?: { x: number; y: number }) => {
    Array.from(files).forEach((file, index) => {
      if (!file.type.startsWith("image/") && !file.type.startsWith("video/")) return;
      const kind = file.type.startsWith("image/") ? "image" : "video";
      const previewUrl = URL.createObjectURL(file);
      const nodePosition = position ? { x: position.x + index * 28, y: position.y + index * 28 } : undefined;
      const id = addNode(kind, previewUrl, nodePosition, file.name.replace(/\.[^.]+$/, ""));
      patchNode(id, { status: "running", uploading: true, error: undefined });
      const upload = kind === "image" ? uploadFile(file, "infinite-canvas") : uploadVideo(file);
      void upload
        .then((url) => {
          URL.revokeObjectURL(previewUrl);
          patchNode(id, { url, status: "done", uploading: false });
          if (index === 0) toast.success("媒体已上传并添加到画布");
        })
        .catch((error) => {
          patchNode(id, { status: "error", uploading: false, error: error instanceof Error ? error.message : "上传失败" });
        });
    });
  };

  const exportDocument = () => {
    const document: CanvasDocument = {
      version: 2,
      nodes,
      edges,
      viewport: instance?.getViewport() ?? { x: 0, y: 0, zoom: 1 },
    };
    const url = URL.createObjectURL(new Blob([JSON.stringify(document, null, 2)], { type: "application/json" }));
    const anchor = window.document.createElement("a");
    anchor.href = url;
    anchor.download = `infinite-canvas-${new Date().toISOString().slice(0, 10)}.json`;
    anchor.click();
    URL.revokeObjectURL(url);
  };

  const importDocument = async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    event.target.value = "";
    if (!file) return;
    try {
      const document = JSON.parse(await file.text()) as CanvasDocument;
      if ((document.version !== 1 && document.version !== 2) || !Array.isArray(document.nodes) || !Array.isArray(document.edges)) {
        throw new Error();
      }
      pushHistory();
      setNodes(document.nodes);
      setEdges(document.edges);
      commitState(document.nodes, document.edges);
      await instance?.setViewport(document.viewport);
      toast.success("画布已导入");
    } catch {
      toast.error("无法识别这个画布文件");
    }
  };

  const resetCanvas = () => {
    if (!window.confirm("确定清空当前画布吗？此操作可以撤销。")) return;
    pushHistory();
    setNodes([]);
    setEdges([]);
    commitState([], []);
  };

  return (
    <div className="flex h-[calc(100vh-7rem)] min-h-[620px] flex-col overflow-hidden rounded-lg border bg-background">
      <header className="flex h-14 shrink-0 items-center justify-between gap-3 border-b px-3 sm:px-4">
        <div className="flex min-w-0 items-center gap-2">
          <span className="flex size-8 shrink-0 items-center justify-center rounded-md bg-emerald-500/12 text-emerald-500">
            <Network className="size-4" />
          </span>
          <div className="min-w-0">
            <h1 className="truncate text-sm font-semibold">无限画布</h1>
            <p className="flex items-center gap-1 text-[11px] text-muted-foreground">
              {saved ? <Check className="size-3 text-emerald-500" /> : <Save className="size-3" />}
              {saved ? "已自动保存" : "保存中"}
            </p>
          </div>
        </div>

        <div className="flex items-center gap-1">
          <Button size="icon-sm" variant="ghost" onClick={undo} disabled={!past.length} title="撤销 (Ctrl+Z)"><Undo2 /></Button>
          <Button size="icon-sm" variant="ghost" onClick={redo} disabled={!future.length} title="重做 (Ctrl+Shift+Z)"><Redo2 /></Button>
          <span className="mx-1 h-5 w-px bg-border" />
          <Button size="icon-sm" variant="ghost" onClick={duplicateSelected} title="复制所选 (Ctrl+D)"><Copy /></Button>
          <Button size="icon-sm" variant="ghost" onClick={deleteSelected} title="删除所选"><Trash2 /></Button>
          <span className="mx-1 hidden h-5 w-px bg-border sm:block" />
          <Button size="sm" variant="outline" className="hidden sm:flex" onClick={() => importInputRef.current?.click()}><FileUp />导入</Button>
          <Button size="sm" variant="outline" onClick={exportDocument}><Download /><span className="hidden sm:inline">导出</span></Button>
          <Button size="icon-sm" variant="ghost" onClick={resetCanvas} title="清空画布"><RotateCcw /></Button>
        </div>
      </header>

      <div className="relative min-h-0 flex-1">
        <ReactFlow<CanvasNode, Edge>
          nodes={nodes}
          edges={edges}
          nodeTypes={nodeTypes}
          onInit={setInstance}
          onNodesChange={onNodesChange}
          onEdgesChange={onEdgesChange}
          onConnect={onConnect}
          onConnectEnd={onConnectEnd}
          connectionLineType={ConnectionLineType.SmoothStep}
          defaultEdgeOptions={{ type: "smoothstep", markerEnd: { type: MarkerType.ArrowClosed } }}
          fitView
          fitViewOptions={{ padding: 0.3 }}
          minZoom={0.1}
          maxZoom={4}
          panOnScroll
          panOnDrag={[1, 2]}
          selectionOnDrag
          selectionMode={SelectionMode.Partial}
          multiSelectionKeyCode={["Control", "Meta", "Shift"]}
          deleteKeyCode={null}
          colorMode="system"
          proOptions={{ hideAttribution: true }}
          onMoveEnd={() => setSaved(false)}
          onDragOver={(event) => {
            event.preventDefault();
            event.dataTransfer.dropEffect = "copy";
            setDragActive(true);
          }}
          onDragLeave={(event) => {
            if (!event.currentTarget.contains(event.relatedTarget as globalThis.Node | null)) setDragActive(false);
          }}
          onDrop={(event) => {
            event.preventDefault();
            setDragActive(false);
            if (!event.dataTransfer.files.length) return;
            handleMediaFiles(event.dataTransfer.files, screenToFlowPosition({ x: event.clientX, y: event.clientY }));
          }}
          onPaneClick={() => setQuickCreate(null)}
        >
          <Background variant={BackgroundVariant.Dots} gap={20} size={1.2} color="var(--border)" />
          <MiniMap
            position="bottom-right"
            pannable
            zoomable
            nodeColor={(node) => nodeColors[(node.data as CanvasNodeData).kind]}
            className="!hidden !rounded-md !border !border-border !bg-background/90 sm:!block"
          />
        </ReactFlow>

        <aside className="absolute left-3 top-3 z-10 flex flex-col gap-1 rounded-lg border bg-background/95 p-1 shadow-lg backdrop-blur">
          <ToolButton icon={<MousePointer2 />} label="选择" onClick={() => toast.info("拖拽框选，Shift 可多选")} />
          <div className="mx-2 h-px bg-border" />
          <ToolButton icon={<Type />} label="文本" onClick={() => addNode("text")} />
          <ToolButton icon={<StickyNote />} label="便签" onClick={() => addNode("note")} />
          <ToolButton icon={<ImageIcon />} label="AI 图片" onClick={() => addNode("image")} />
          <ToolButton icon={<Video />} label="AI 视频" onClick={() => addNode("video")} />
          <div className="mx-2 h-px bg-border" />
          <ToolButton icon={<FilePlus2 />} label="上传素材" onClick={() => fileInputRef.current?.click()} />
        </aside>

        {dragActive ? (
          <div className="pointer-events-none absolute inset-3 z-20 flex items-center justify-center rounded-lg border-2 border-dashed border-emerald-500 bg-emerald-500/8">
            <div className="rounded-md border bg-background px-4 py-3 text-center shadow-lg">
              <FilePlus2 className="mx-auto mb-1 size-5 text-emerald-500" />
              <p className="text-sm font-medium">松开以添加图片或视频</p>
            </div>
          </div>
        ) : null}

        {quickCreate ? (
          <div
            className="absolute z-30 w-44 rounded-lg border bg-background p-1.5 shadow-xl"
            style={{
              left: Math.min(Math.max(quickCreate.screenPosition.x - 280, 12), window.innerWidth - 200),
              top: Math.min(Math.max(quickCreate.screenPosition.y - 120, 12), window.innerHeight - 260),
            }}
            onPointerDown={(event) => event.stopPropagation()}
          >
            <p className="px-2 py-1.5 text-[11px] font-medium text-muted-foreground">创建并连接</p>
            {([
              ["text", "文本", Type],
              ["note", "便签", StickyNote],
              ["image", "AI 图片", ImageIcon],
              ["video", "AI 视频", Video],
            ] as const).map(([kind, label, Icon]) => (
              <button
                key={kind}
                type="button"
                onClick={() => createConnectedNode(kind)}
                className="flex h-9 w-full items-center gap-2 rounded-md px-2 text-sm hover:bg-muted"
              >
                <Icon className="size-4 text-emerald-500" />
                {label}
              </button>
            ))}
          </div>
        ) : null}

        <div className="absolute bottom-3 left-1/2 z-10 flex -translate-x-1/2 items-center gap-1 rounded-lg border bg-background/95 p-1 shadow-lg backdrop-blur">
          <Button size="icon-sm" variant="ghost" onClick={() => void zoomOut()} title="缩小"><ZoomOut /></Button>
          <Button size="icon-sm" variant="ghost" onClick={() => void zoomIn()} title="放大"><ZoomIn /></Button>
          <Button size="icon-sm" variant="ghost" onClick={() => void fitView({ padding: 0.25 })} title="适应内容"><Focus /></Button>
        </div>

        {nodes.length === 0 ? (
          <div className="pointer-events-none absolute inset-0 flex items-center justify-center">
            <div className="pointer-events-auto max-w-sm text-center">
              <span className="mx-auto mb-4 flex size-12 items-center justify-center rounded-lg border bg-background text-emerald-500 shadow-sm"><Network /></span>
              <h2 className="text-base font-semibold">开始组织你的创意</h2>
              <p className="mt-1 text-sm leading-6 text-muted-foreground">添加文本、便签或媒体，通过节点连线构建创作脉络。</p>
              <Button className="mt-4" onClick={() => addNode("text")}><Plus />添加第一个节点</Button>
            </div>
          </div>
        ) : null}

        <input ref={fileInputRef} type="file" accept="image/*,video/*" multiple hidden onChange={(event) => event.target.files && handleMediaFiles(event.target.files)} />
        <input ref={importInputRef} type="file" accept="application/json,.json" hidden onChange={importDocument} />
      </div>
      {mediaPreview ? <MediaPreviewOverlay preview={mediaPreview} onClose={() => setMediaPreview(null)} /> : null}
    </div>
  );
}

export function InfiniteCanvas() {
  return (
    <ReactFlowProvider>
      <CanvasWorkspace />
    </ReactFlowProvider>
  );
}
