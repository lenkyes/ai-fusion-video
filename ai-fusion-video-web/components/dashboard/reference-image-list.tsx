"use client";

import {
  type ChangeEvent,
  type DragEvent,
  useEffect,
  useRef,
  useState,
} from "react";
import { ImagePlus, Link, Loader2, Plus, X } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { resolveMediaUrl } from "@/lib/api/client";
import { uploadFile } from "@/lib/api/storage";
import { cn } from "@/lib/utils";

interface ReferenceImageListProps {
  value: string[];
  onChange: (value: string[]) => void;
}

interface PendingImage {
  id: string;
  previewUrl: string;
}

export function ReferenceImageList({
  value,
  onChange,
}: ReferenceImageListProps) {
  const fileInputRef = useRef<HTMLInputElement>(null);
  const valueRef = useRef(value);
  const [pending, setPending] = useState<PendingImage[]>([]);
  const [linkVisible, setLinkVisible] = useState(false);
  const [linkValue, setLinkValue] = useState("");
  const [dragging, setDragging] = useState(false);

  useEffect(() => {
    valueRef.current = value;
  }, [value]);

  const commit = (next: string[]) => {
    const normalized = next.map((url) => url.trim()).filter(Boolean);
    valueRef.current = normalized;
    onChange(normalized);
  };

  const uploadFiles = async (files: File[]) => {
    const images = files.filter((file) => file.type.startsWith("image/"));
    if (!images.length) {
      toast.error("请选择图片文件");
      return;
    }

    const entries = images.map((file) => ({
      file,
      id: `${crypto.randomUUID()}`,
      previewUrl: URL.createObjectURL(file),
    }));
    setPending((current) => [
      ...current,
      ...entries.map(({ id, previewUrl }) => ({ id, previewUrl })),
    ]);

    await Promise.all(
      entries.map(async ({ file, id, previewUrl }) => {
        try {
          const uploadedUrl = await uploadFile(file, "image-generation");
          commit([...valueRef.current, uploadedUrl]);
        } catch (error) {
          toast.error(
            error instanceof Error ? error.message : `${file.name} 上传失败`,
          );
        } finally {
          URL.revokeObjectURL(previewUrl);
          setPending((current) => current.filter((item) => item.id !== id));
        }
      }),
    );
  };

  const handleFileChange = (event: ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(event.target.files ?? []);
    event.target.value = "";
    void uploadFiles(files);
  };

  const handleDrop = (event: DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    setDragging(false);
    void uploadFiles(Array.from(event.dataTransfer.files));
  };

  const addLink = () => {
    const url = linkValue.trim();
    if (!url) return;
    commit([...valueRef.current, url]);
    setLinkValue("");
    setLinkVisible(false);
  };

  return (
    <div
      className={cn(
        "rounded-lg border border-dashed bg-muted/10 p-3 transition-colors",
        dragging && "border-primary bg-primary/5",
      )}
      onDragEnter={(event) => {
        event.preventDefault();
        setDragging(true);
      }}
      onDragOver={(event) => event.preventDefault()}
      onDragLeave={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget as Node)) {
          setDragging(false);
        }
      }}
      onDrop={handleDrop}
    >
      <div className="mb-2 flex items-center justify-between gap-3">
        <span className="text-xs font-medium">参考图</span>
        <div className="flex items-center gap-1">
          <Button
            type="button"
            size="icon-xs"
            variant="ghost"
            title="添加图片链接"
            aria-label="添加图片链接"
            onClick={() => setLinkVisible((visible) => !visible)}
          >
            <Link />
          </Button>
          <Button
            type="button"
            size="xs"
            variant="outline"
            onClick={() => fileInputRef.current?.click()}
          >
            <Plus />
            添加图片
          </Button>
        </div>
      </div>

      {linkVisible && (
        <div className="mb-3 flex gap-2">
          <Input
            value={linkValue}
            className="h-8 rounded-md text-xs"
            placeholder="粘贴图片链接"
            autoFocus
            onChange={(event) => setLinkValue(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter") {
                event.preventDefault();
                addLink();
              }
            }}
          />
          <Button type="button" size="sm" onClick={addLink}>
            添加
          </Button>
        </div>
      )}

      <div className="flex min-h-20 flex-wrap content-start items-start gap-2">
        {value.map((url, index) => {
          const src = resolveMediaUrl(url);
          return (
            <div
              key={`${url}-${index}`}
              className="group relative size-20 shrink-0 overflow-hidden rounded-md border bg-muted"
            >
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img
                src={src ?? url}
                alt={`参考图 ${index + 1}`}
                className="size-full object-cover"
              />
              <button
                type="button"
                title={`移除参考图 ${index + 1}`}
                aria-label={`移除参考图 ${index + 1}`}
                className="absolute right-1 top-1 grid size-6 place-items-center rounded-full bg-black/65 text-white opacity-0 shadow-sm backdrop-blur-sm transition-opacity hover:bg-destructive focus-visible:opacity-100 group-hover:opacity-100"
                onClick={() =>
                  commit(valueRef.current.filter((_, itemIndex) => itemIndex !== index))
                }
              >
                <X className="size-3.5" />
              </button>
            </div>
          );
        })}

        {pending.map((item) => (
          <div
            key={item.id}
            className="relative size-20 shrink-0 overflow-hidden rounded-md border bg-muted"
          >
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img
              src={item.previewUrl}
              alt="正在上传的参考图"
              className="size-full object-cover opacity-60"
            />
            <div className="absolute inset-0 grid place-items-center bg-black/15">
              <Loader2 className="size-5 animate-spin text-white drop-shadow" />
            </div>
          </div>
        ))}

        <button
          type="button"
          className="flex size-20 shrink-0 flex-col items-center justify-center gap-1 rounded-md border border-dashed text-muted-foreground transition-colors hover:border-primary/60 hover:bg-primary/5 hover:text-primary"
          onClick={() => fileInputRef.current?.click()}
        >
          <ImagePlus className="size-5" />
          <span className="text-[11px]">上传图片</span>
        </button>
      </div>

      <input
        ref={fileInputRef}
        type="file"
        accept="image/*"
        multiple
        className="hidden"
        onChange={handleFileChange}
      />
      <p className="mt-2 text-[11px] text-muted-foreground">
        支持一次选择或拖入多张图片
      </p>
    </div>
  );
}
