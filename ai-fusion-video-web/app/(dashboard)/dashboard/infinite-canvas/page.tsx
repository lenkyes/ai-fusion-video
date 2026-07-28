"use client";

import { useFullWidth } from "@/lib/hooks/use-layout";
import { InfiniteCanvas } from "./infinite-canvas";

export default function InfiniteCanvasPage() {
  useFullWidth(true);
  return <InfiniteCanvas />;
}
