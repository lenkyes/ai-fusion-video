"use client";

import { useEffect, useState } from "react";
import { useRouter, usePathname } from "next/navigation";
import {
  LayoutDashboard,
  BarChart3,
  FolderKanban,
  BookOpen,
  Film,
  Images,
  Users,
  Settings,
  ArrowLeft,
  Bot,
  HardDrive,
  ListChecks,
  Clapperboard,
  WandSparkles,
} from "lucide-react";
import { motion, AnimatePresence } from "framer-motion";
import { cn } from "@/lib/utils";
import { projectApi, type Project } from "@/lib/api/project";
import { useAuthStore } from "@/lib/store/auth-store";

// ========== 各模块的二级菜单配置 ==========

interface SidebarItem {
  key: string;
  label: string;
  icon: typeof LayoutDashboard;
  href: string;
  iconColor: string;
}

const dashboardItems: SidebarItem[] = [
  { key: "overview", label: "总览", icon: LayoutDashboard, href: "/dashboard", iconColor: "text-blue-400" },
  { key: "assistant", label: "AI 助手", icon: Bot, href: "/dashboard/assistant", iconColor: "text-purple-400" },
  { key: "image-gen", label: "AI生图", icon: WandSparkles, href: "/dashboard/image-gen", iconColor: "text-pink-400" },
  { key: "analytics", label: "数据分析", icon: BarChart3, href: "/dashboard/analytics", iconColor: "text-purple-400" },
  { key: "tasks", label: "任务中心", icon: ListChecks, href: "/dashboard/tasks", iconColor: "text-cyan-400" },
];

const projectListItems: SidebarItem[] = [
  { key: "list", label: "项目列表", icon: FolderKanban, href: "/projects", iconColor: "text-purple-400" },
];

const projectDetailItems: SidebarItem[] = [
  { key: "", label: "概览", icon: LayoutDashboard, href: "", iconColor: "text-blue-400" },
  { key: "scripts", label: "剧本", icon: BookOpen, href: "/scripts", iconColor: "text-purple-400" },
  { key: "storyboards", label: "分镜", icon: Film, href: "/storyboards", iconColor: "text-cyan-400" },
  { key: "assets", label: "资产", icon: Images, href: "/assets", iconColor: "text-orange-400" },
  { key: "costs", label: "成本", icon: BarChart3, href: "/costs", iconColor: "text-emerald-400" },
  // { key: "members", label: "成员", icon: Users, href: "/members", iconColor: "text-green-400" },
  { key: "settings", label: "设置", icon: Settings, href: "/settings", iconColor: "text-rose-400" },
];

const assetItems: SidebarItem[] = [
  { key: "list", label: "全部资产", icon: Images, href: "/assets", iconColor: "text-orange-400" },
];

// ========== 侧边栏组件 ==========

export function SidebarNav({ onNavigate, project: projectProp }: { onNavigate?: () => void; project?: Project | null }) {
  const router = useRouter();
  const pathname = usePathname();
  const currentUser = useAuthStore((state) => state.user);
  const isAdmin = currentUser?.roles?.includes("admin") ?? false;

  const projectMatch = pathname.match(/^\/projects\/(\d+)/);
  const projectId = projectMatch ? Number(projectMatch[1]) : null;
  const [projectLocalState, setProjectLocalState] = useState<{
    id: number;
    project: Project;
  } | null>(null);

  const settingsItems: SidebarItem[] = isAdmin
    ? [
        { key: "general", label: "通用设置", icon: Settings, href: "/settings/general", iconColor: "text-green-400" },
        { key: "users", label: "用户列表", icon: Users, href: "/settings/users", iconColor: "text-cyan-400" },
        { key: "profile", label: "个人设置", icon: Users, href: "/settings/profile", iconColor: "text-blue-400" },
        { key: "ai-models", label: "AI 模型", icon: Bot, href: "/settings/ai-models", iconColor: "text-purple-400" },
        { key: "storage", label: "存储配置", icon: HardDrive, href: "/settings/storage", iconColor: "text-orange-400" },
        { key: "video-templates", label: "视频模板", icon: Clapperboard, href: "/settings/video-templates", iconColor: "text-rose-400" },
      ]
    : [
        { key: "profile", label: "个人设置", icon: Users, href: "/settings/profile", iconColor: "text-blue-400" },
      ];

  // 若外部已传入 project，则不在组件内自行请求
  const project =
    projectProp !== undefined
      ? projectProp
      : projectLocalState?.id === projectId
        ? projectLocalState.project
        : null;

  useEffect(() => {
    if (projectProp !== undefined) return; // 由外部管理，跳过
    if (!projectId) return;

    let cancelled = false;
    projectApi.get(projectId)
      .then((projectData) => {
        if (!cancelled) {
          setProjectLocalState({ id: projectId, project: projectData });
        }
      })
      .catch(() => {});

    return () => {
      cancelled = true;
    };
  }, [projectId, projectProp]);

  let items: SidebarItem[] = [];
  let sectionTitle = "";
  let backAction: (() => void) | null = null;

  if (projectId) {
    sectionTitle = project?.name || "加载中...";
    items = projectDetailItems.map((item) => ({
      ...item,
      href: `/projects/${projectId}${item.href}`,
    }));
    backAction = () => {
      router.push("/projects");
      onNavigate?.();
    };
  } else if (pathname.startsWith("/dashboard")) {
    sectionTitle = "仪表盘";
    items = dashboardItems;
  } else if (pathname.startsWith("/projects")) {
    sectionTitle = "项目";
    items = projectListItems;
  } else if (pathname.startsWith("/assets")) {
    sectionTitle = "资产";
    items = assetItems;
  } else if (pathname.startsWith("/settings")) {
    sectionTitle = "系统设置";
    items = settingsItems;
  }

  const getIsActive = (href: string) => {
    if (projectId) {
      const basePath = `/projects/${projectId}`;
      if (href === basePath) return pathname === basePath;
      return pathname.startsWith(href);
    }
    return pathname === href;
  };

  const handleNav = (href: string) => {
    router.push(href);
    onNavigate?.();
  };

  return (
    <div
      className={cn(
        "w-full h-[calc(100vh-6rem)] rounded-2xl p-2",
        "bg-linear-to-b from-background/80 to-background/40",
        "backdrop-blur-lg border border-border/40",
        "shadow-lg"
      )}
    >
      <AnimatePresence mode="wait">
        <motion.div
          key={sectionTitle}
          initial={{ opacity: 0, y: 8 }}
          animate={{ opacity: 1, y: 0 }}
          exit={{ opacity: 0 }}
          transition={{ duration: 0.2, ease: "easeOut" }}
        >
          {/* 标题区 */}
          <div className="px-3 pt-3 pb-3">
            {backAction && (
              <>
                <button
                  onClick={backAction}
                  className="flex items-center gap-1.5 text-xs text-muted-foreground hover:text-foreground transition-colors mb-2 group"
                >
                  <ArrowLeft className="h-3.5 w-3.5 group-hover:-translate-x-0.5 transition-transform" />
                  返回项目列表
                </button>
                <div className="border-b border-border/30 -mx-3 mb-2" />
              </>
            )}
            <h3 className="text-sm font-semibold truncate">
              {projectId &&  (
                "项目: "
            )}
              {sectionTitle}
            </h3>
            {projectId && project?.description && (
              <p className="text-xs text-muted-foreground/60 mt-0.5 line-clamp-1">
                {project.description}
              </p>
            )}
          </div>

          {/* 导航项 */}
          <nav className="space-y-0.5 px-1">
            {items.map((item) => {
              const Icon = item.icon;
              const isActive = getIsActive(item.href);

              return (
                <button
                  key={item.key}
                  onClick={() => handleNav(item.href)}
                  className={cn(
                    "w-full flex items-center gap-2.5 px-3 py-2 rounded-xl text-sm",
                    "transition-all duration-150 relative",
                    isActive
                      ? "font-medium text-foreground bg-foreground/6"
                      : "text-muted-foreground hover:text-foreground hover:bg-foreground/3"
                  )}
                >
                  {/* 选中指示条，颜色与菜单项图标一致 */}
                  <span
                    className={cn(
                      "transition-colors duration-200",
                      isActive ? item.iconColor : "text-transparent",
                    )}
                  >
                    <div className="w-0.5 h-5 rounded-full bg-current" />
                  </span>
                  <span className={cn("transition-colors", isActive ? item.iconColor : "")}>
                    <Icon className="h-[18px] w-[18px]" />
                  </span>
                  <span>{item.label}</span>
                </button>
              );
            })}
          </nav>
        </motion.div>
      </AnimatePresence>
    </div>
  );
}
