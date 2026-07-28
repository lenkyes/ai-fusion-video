"use client";

import {
  Background,
  BackgroundVariant,
  ConnectionLineType,
  Handle,
  MarkerType,
  MiniMap,
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
  type Node,
  type NodeChange,
  type NodeProps,
  type ReactFlowInstance,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import {
  Check,
  Copy,
  Download,
  FileUp,
  Focus,
  ImageIcon,
  Link2,
  MousePointer2,
  Network,
  StickyNote,
  Plus,
  Redo2,
  RotateCcw,
  Save,
  Trash2,
  Type,
  Undo2,
  Video,
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
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

type CanvasNodeKind = "text" | "note" | "image" | "video";

type CanvasNodeData = {
  kind: CanvasNodeKind;
  title: string;
  content?: string;
  url?: string;
};

type CanvasNode = Node<CanvasNodeData, "canvasNode">;

type CanvasDocument = {
  version: 1;
  nodes: CanvasNode[];
  edges: Edge[];
  viewport: { x: number; y: number; zoom: number };
};

type HistoryEntry = Pick<CanvasDocument, "nodes" | "edges">;

const STORAGE_KEY = "ai-fusion-infinite-canvas-v1";
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
    position: { x: 40, y: 80 },
    data: {
      kind: "text",
      title: "创意起点",
      content: "双击文字开始编辑，把提示词、参考图和视频用连线组织起来。",
    },
  },
  {
    id: "welcome-note",
    type: "canvasNode",
    dragHandle: ".drag-handle",
    position: { x: 410, y: 190 },
    data: {
      kind: "note",
      title: "镜头备注",
      content: "从左侧工具栏添加内容，滚轮缩放，拖动画布移动视角。",
    },
  },
];

const initialEdges: Edge[] = [
  {
    id: "welcome-edge",
    source: "welcome-prompt",
    target: "welcome-note",
    type: "smoothstep",
    markerEnd: { type: MarkerType.ArrowClosed },
  },
];

function cloneHistory(nodes: CanvasNode[], edges: Edge[]): HistoryEntry {
  return {
    nodes: structuredClone(nodes),
    edges: structuredClone(edges),
  };
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

  return (
    <article
      className={cn(
        "group w-[300px] overflow-hidden rounded-lg border bg-card shadow-sm transition-shadow",
        selected ? "border-emerald-500 shadow-lg shadow-emerald-500/10" : "border-border/70",
      )}
    >
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
        <div className="relative aspect-video bg-muted/40">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={data.url} alt={data.title} className="h-full w-full object-contain" />
        </div>
      ) : null}

      {data.kind === "video" && data.url ? (
        <div className="nodrag nowheel aspect-video bg-black">
          <video src={data.url} controls className="h-full w-full object-contain" />
        </div>
      ) : null}

      {(data.kind === "text" || data.kind === "note") && (
        <textarea
          value={data.content ?? ""}
          onChange={(event) => updateData({ content: event.target.value })}
          placeholder={data.kind === "text" ? "输入提示词或文本..." : "记录创意和待办..."}
          className={cn(
            "nodrag nowheel min-h-32 w-full resize-none bg-transparent p-3 text-sm leading-6 outline-none",
            data.kind === "note" && "bg-amber-500/5",
          )}
        />
      )}

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

function CanvasWorkspace() {
  const [nodes, setNodes] = useState<CanvasNode[]>(initialNodes);
  const [edges, setEdges] = useState<Edge[]>(initialEdges);
  const [instance, setInstance] = useState<ReactFlowInstance<CanvasNode, Edge> | null>(null);
  const [hydrated, setHydrated] = useState(false);
  const [saved, setSaved] = useState(true);
  const [past, setPast] = useState<HistoryEntry[]>([]);
  const [future, setFuture] = useState<HistoryEntry[]>([]);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const importInputRef = useRef<HTMLInputElement>(null);
  const historyRef = useRef<HistoryEntry>(cloneHistory(initialNodes, initialEdges));
  const draggingRef = useRef(false);
  const didHydrateRef = useRef(false);
  const { screenToFlowPosition, zoomIn, zoomOut, fitView } = useReactFlow<CanvasNode, Edge>();

  const pushHistory = useCallback(() => {
    setPast((entries) => [...entries.slice(-49), historyRef.current]);
    setFuture([]);
  }, []);

  const commitState = useCallback((nextNodes: CanvasNode[], nextEdges: Edge[]) => {
    historyRef.current = cloneHistory(nextNodes, nextEdges);
    setSaved(false);
  }, []);

  const addNode = useCallback(
    (kind: CanvasNodeKind, url?: string, position?: { x: number; y: number }, title?: string) => {
      pushHistory();
      const nextNode: CanvasNode = {
        id: crypto.randomUUID(),
        type: "canvasNode",
        dragHandle: ".drag-handle",
        position: position ?? screenToFlowPosition({ x: window.innerWidth / 2, y: window.innerHeight / 2 }),
        data: {
          kind,
          title: title ?? ({ text: "文本", note: "便签", image: "图片", video: "视频" }[kind]),
          content: kind === "text" || kind === "note" ? "" : undefined,
          url,
        },
      };
      setNodes((current) => {
        const next = [...current.map((node) => ({ ...node, selected: false })), { ...nextNode, selected: true }];
        commitState(next, edges);
        return next;
      });
    },
    [commitState, edges, pushHistory, screenToFlowPosition],
  );

  useEffect(() => {
    if (!instance || didHydrateRef.current) return;
    didHydrateRef.current = true;
    try {
      const savedDocument = localStorage.getItem(STORAGE_KEY);
      if (savedDocument) {
        const document = JSON.parse(savedDocument) as CanvasDocument;
        if (document.version === 1 && Array.isArray(document.nodes) && Array.isArray(document.edges)) {
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
        version: 1,
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
      setNodes((current) => {
        const next = current.map((node) =>
          node.id === id ? { ...node, data: { ...node.data, ...patch } } : node,
        );
        commitState(next, edges);
        return next;
      });
    };
    window.addEventListener("infinite-canvas:update-node", updateNode);
    return () => window.removeEventListener("infinite-canvas:update-node", updateNode);
  }, [commitState, edges]);

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

  const handleMediaFiles = (files: FileList | File[]) => {
    Array.from(files).forEach((file, index) => {
      if (!file.type.startsWith("image/") && !file.type.startsWith("video/")) return;
      const kind = file.type.startsWith("image/") ? "image" : "video";
      addNode(kind, URL.createObjectURL(file), undefined, file.name.replace(/\.[^.]+$/, ""));
      if (index === 0) toast.success("媒体已添加到画布");
    });
  };

  const exportDocument = () => {
    const document: CanvasDocument = {
      version: 1,
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
      if (document.version !== 1 || !Array.isArray(document.nodes) || !Array.isArray(document.edges)) {
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

      <div
        className="relative min-h-0 flex-1"
        onDragOver={(event) => event.preventDefault()}
        onDrop={(event) => {
          event.preventDefault();
          handleMediaFiles(event.dataTransfer.files);
        }}
      >
        <ReactFlow<CanvasNode, Edge>
          nodes={nodes}
          edges={edges}
          nodeTypes={nodeTypes}
          onInit={setInstance}
          onNodesChange={onNodesChange}
          onEdgesChange={onEdgesChange}
          onConnect={onConnect}
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
          <ToolButton icon={<ImageIcon />} label="图片" onClick={() => fileInputRef.current?.click()} />
          <ToolButton icon={<Video />} label="视频" onClick={() => fileInputRef.current?.click()} />
        </aside>

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
