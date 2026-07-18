"use client";

import { useEffect, useState, useMemo, useRef, useSyncExternalStore } from "react";
import { useRouter, usePathname } from "next/navigation";
import { motion, AnimatePresence } from "framer-motion";
import { Menu, X } from "lucide-react";
import { useAuthStore } from "@/lib/store/auth-store";
import { AppHeader } from "@/components/dashboard/app-header";
import { VersionUpdateNotifier } from "@/components/dashboard/version-update-notifier";
import { SidebarNav } from "@/components/dashboard/sidebar-nav";
import { cn } from "@/lib/utils";
import { projectApi, type Project } from "@/lib/api/project";
import { LayoutContext, useLayoutState } from "@/lib/hooks/use-layout";

export default function DashboardLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const router = useRouter();
  const pathname = usePathname();
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated());
  const refreshSession = useAuthStore((s) => s.refreshSession);
  const authHydrated = useSyncExternalStore(
    (onStoreChange) => {
      const unsubStart = useAuthStore.persist.onHydrate(onStoreChange);
      const unsubFinish = useAuthStore.persist.onFinishHydration(onStoreChange);
      return () => {
        unsubStart();
        unsubFinish();
      };
    },
    () => useAuthStore.persist.hasHydrated(),
    () => false
  );
  const [sessionReady, setSessionReady] = useState(false);
  const lastSessionRefreshAtRef = useRef(0);
  const [sidebarRoute, setSidebarRoute] = useState<string | null>(null);
  const [projectState, setProjectState] = useState<{ id: number; project: Project } | null>(null);
  const sidebarOpen = sidebarRoute === pathname;
  const currentProjectId = useMemo(() => {
    const match = pathname.match(/^\/projects\/(\d+)/);
    return match ? Number(match[1]) : null;
  }, [pathname]);
  const currentProject = projectState?.id === currentProjectId ? projectState.project : null;

  // 布局宽度控制：子页面通过 useFullWidth(condition) 驱动
  const { fullWidth, setFullWidth } = useLayoutState();
  const layoutCtx = useMemo(
    () => ({ fullWidth, setFullWidth }),
    [fullWidth, setFullWidth]
  );

  useEffect(() => {
    if (!authHydrated) {
      setSessionReady(false);
      return;
    }
    if (!isAuthenticated) {
      setSessionReady(false);
      return;
    }

    let cancelled = false;
    setSessionReady(false);
    lastSessionRefreshAtRef.current = Date.now();

    void refreshSession().finally(() => {
      if (!cancelled) {
        setSessionReady(useAuthStore.getState().isAuthenticated());
      }
    });

    return () => {
      cancelled = true;
    };
  }, [authHydrated, isAuthenticated, refreshSession]);

  useEffect(() => {
    if (!authHydrated || !isAuthenticated) {
      return;
    }

    const renewVisibleSession = () => {
      if (document.visibilityState === "hidden") {
        return;
      }
      const now = Date.now();
      if (now - lastSessionRefreshAtRef.current < 5 * 60 * 1000) {
        return;
      }
      lastSessionRefreshAtRef.current = now;
      void refreshSession();
    };

    window.addEventListener("focus", renewVisibleSession);
    document.addEventListener("visibilitychange", renewVisibleSession);
    return () => {
      window.removeEventListener("focus", renewVisibleSession);
      document.removeEventListener("visibilitychange", renewVisibleSession);
    };
  }, [authHydrated, isAuthenticated, refreshSession]);

  // 在 layout 层统一请求 project 数据，供桌面/移动端 SidebarNav 共享
  useEffect(() => {
    if (!sessionReady || !isAuthenticated || currentProjectId === null) {
      return;
    }
    let cancelled = false;
    projectApi.get(currentProjectId)
      .then((project) => {
        if (!cancelled) {
          setProjectState({ id: currentProjectId, project });
        }
      })
      .catch(() => {});

    return () => {
      cancelled = true;
    };
  }, [currentProjectId, isAuthenticated, sessionReady]);

  // 运行时登出检测：用户主动登出后跳转到登录页
  // 初始进入时的认证保护由 middleware 处理
  useEffect(() => {
    if (authHydrated && !isAuthenticated) {
      router.replace("/login");
    }
  }, [authHydrated, isAuthenticated, router]);

  const ready = authHydrated && isAuthenticated && sessionReady;

  return (
    <LayoutContext value={layoutCtx}>
      <div className="h-dvh overflow-hidden flex flex-col bg-background">
        <VersionUpdateNotifier />

        {/* 顶部浮动导航栏 */}
        <motion.div
          initial={{ opacity: 0, y: -20 }}
          animate={ready ? { opacity: 1, y: 0 } : { opacity: 0, y: -20 }}
          transition={{ duration: 0.5, ease: [0.25, 0.46, 0.45, 0.94] }}
        >
          {ready ? <AppHeader /> : null}
        </motion.div>

        {/* 侧边栏 + 主内容 */}
        <motion.div
          className="flex pt-20 flex-1 min-h-0"
          initial={{ opacity: 0 }}
          animate={ready ? { opacity: 1 } : { opacity: 0 }}
          transition={{ duration: 0.4, delay: 0.15 }}
        >
          {/* 桌面端：浮动侧边栏卡片 */}
          {/* lg(1024px): ~240px card | xl(1280px): ~270px card | 2xl+(1440px): 300px card */}
          {ready && (
            <div className="hidden lg:block shrink-0 w-[clamp(272px,23vw,332px)] px-4 pt-1 self-start">
              <SidebarNav project={currentProject} />
            </div>
          )}

          {/* 移动端：浮动抽屉 */}
          <AnimatePresence>
            {ready && sidebarOpen && (
              <>
                <motion.div
                  initial={{ opacity: 0 }}
                  animate={{ opacity: 1 }}
                  exit={{ opacity: 0 }}
                  transition={{ duration: 0.2 }}
                  className="fixed inset-0 z-40 bg-white/40 backdrop-blur-sm lg:hidden"
                  onClick={() => setSidebarRoute(null)}
                />
                <motion.div
                  initial={{ opacity: 0, x: -20 }}
                  animate={{ opacity: 1, x: 0 }}
                  exit={{ opacity: 0, x: -20 }}
                  transition={{ duration: 0.2, ease: "easeOut" }}
                  className="fixed left-2 top-20 bottom-3 z-50 lg:hidden w-[min(86vw,300px)]"
                >
                  <SidebarNav project={currentProject} onNavigate={() => setSidebarRoute(null)} />
                </motion.div>
              </>
            )}
          </AnimatePresence>

          {/* 移动端菜单按钮 */}
          {ready && (
            <button
              onClick={() => setSidebarRoute(sidebarOpen ? null : pathname)}
              className={cn(
                "fixed left-3 z-60 lg:hidden",
                "h-11 w-11 rounded-full flex items-center justify-center",
                "bg-primary text-primary-foreground shadow-lg shadow-primary/20",
                "hover:shadow-primary/30 hover:scale-105",
                "active:scale-95 transition-all duration-200"
              )}
              style={{ bottom: "max(1rem, env(safe-area-inset-bottom))" }}
              aria-label={sidebarOpen ? "关闭侧边导航" : "打开侧边导航"}
            >
              {sidebarOpen ? (
                <X className="h-5 w-5" />
              ) : (
                <Menu className="h-5 w-5" />
              )}
            </button>
          )}

          {/* 主内容区 */}
          <main className="flex-1 min-w-0 min-h-0 px-3 pb-[max(1rem,env(safe-area-inset-bottom))] pt-2 sm:px-5 sm:py-4 lg:px-8 overflow-auto">
            <div className={cn("w-full h-full mx-auto transition-[max-width] duration-300 ease-in-out", fullWidth ? "max-w-full" : "max-w-7xl")}>
              {ready ? children : null}
            </div>
          </main>
        </motion.div>
      </div>
    </LayoutContext>
  );
}
