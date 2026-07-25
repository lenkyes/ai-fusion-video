"use client";
import { Plus, X } from "lucide-react";
import ImageInput from "@/components/dashboard/image-input";

export function ReferenceImageList({ value, onChange }: { value: string[]; onChange: (value: string[]) => void }) {
  const update = (index: number, url: string) => {
    const next = value.map((item, i) => (i === index ? url : item));
    if (url && index === value.length - 1) next.push("");
    onChange(next);
  };
  return <div className="rounded-xl border border-dashed p-3"><div className="mb-2 flex items-center justify-between text-xs"><b>参考图（可选，可添加多张）</b><button type="button" className="text-primary" onClick={() => onChange([...value, ""])}><Plus className="mr-1 inline h-3 w-3" />添加</button></div>{(value.length ? value : [""]).map((url, index) => <div key={index} className="mb-2 flex items-start gap-2"><div className="min-w-0 flex-1"><ImageInput value={url} onChange={(next) => update(index, next)} uploadSubDir="image-generation" /></div>{value.length > 1 && <button type="button" onClick={() => onChange(value.filter((_, i) => i !== index))}><X className="h-4 w-4 text-destructive" /></button>}</div>)}<p className="text-[11px] text-muted-foreground">上传或链接成功后会自动出现下一项；不添加参考图时为文生图。</p></div>;
}
