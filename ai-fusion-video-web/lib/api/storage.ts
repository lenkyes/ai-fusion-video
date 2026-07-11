import axios from "axios";
import { http, API_BASE_URL } from "./client";

// ============================================================
// 类型定义
// ============================================================

export interface StorageConfig {
  id: number;
  name: string;
  type: string;
  endpoint?: string;
  bucketName?: string;
  accessKey?: string;
  secretKey?: string;
  region?: string;
  basePath?: string;
  customDomain?: string;
  isDefault?: boolean;
  status: number;
  remark?: string;
  createTime?: string;
  updateTime?: string;
}

export interface StorageConfigSaveReq {
  id?: number;
  name: string;
  type?: string;
  endpoint?: string;
  bucketName?: string;
  accessKey?: string;
  secretKey?: string;
  region?: string;
  basePath?: string;
  customDomain?: string;
  isDefault?: boolean;
  status?: number;
  remark?: string;
}

// ============================================================
// 存储类型选项
// ============================================================

export const STORAGE_TYPE_OPTIONS = [
  { value: "local", label: "本地存储", description: "文件保存在服务器本地磁盘" },
  { value: "s3", label: "S3 兼容存储", description: "阿里云 OSS / 腾讯 COS / AWS S3 / MinIO 等" },
] as const;

export const STORAGE_TYPE_LABELS: Record<string, string> = {
  local: "本地存储",
  s3: "S3 兼容",
};

// ============================================================
// API
// ============================================================

export const storageConfigApi = {
  async create(data: StorageConfigSaveReq): Promise<number> {
    return http.post("/api/storage/config/create", data);
  },

  async update(data: StorageConfigSaveReq): Promise<boolean> {
    return http.put("/api/storage/config/update", data);
  },

  async delete(id: number): Promise<boolean> {
    return http.delete("/api/storage/config/delete", { params: { id } });
  },

  async get(id: number): Promise<StorageConfig> {
    return http.get("/api/storage/config/get", { params: { id } });
  },

  async list(): Promise<StorageConfig[]> {
    return http.get("/api/storage/config/list");
  },

  async setDefault(id: number): Promise<boolean> {
    return http.put("/api/storage/config/set-default", null, { params: { id } });
  },
};

export async function uploadFile(
  file: File,
  subDir: string = "uploads"
): Promise<string> {
  const formData = new FormData();
  formData.append("file", file);
  formData.append("subDir", subDir);

  const token = (() => {
    if (typeof window === "undefined") return null;
    try {
      const stored = localStorage.getItem("auth-storage");
      if (stored) return JSON.parse(stored)?.state?.token;
    } catch {
      // ignore
    }
    return null;
  })();

  const resp = await axios.post(`${API_BASE_URL}/api/storage/upload`, formData, {
    headers: {
      "Content-Type": "multipart/form-data",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
  });

  const result = resp.data;
  if (result.code !== 0) {
    throw new Error(result.msg || "上传失败");
  }
  return result.data;
}

export async function uploadAudio(
  file: File,
  onProgress?: (percent: number) => void
): Promise<string> {
  const formData = new FormData();
  formData.append("file", file);
  const token = (() => {
    if (typeof window === "undefined") return null;
    try {
      const stored = localStorage.getItem("auth-storage");
      return stored ? JSON.parse(stored)?.state?.token : null;
    } catch {
      return null;
    }
  })();
  const resp = await axios.post(`${API_BASE_URL}/api/storage/upload-audio`, formData, {
    headers: {
      "Content-Type": "multipart/form-data",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    timeout: 10 * 60 * 1000,
    onUploadProgress: (event) => {
      if (event.total && onProgress) onProgress(Math.round(event.loaded * 100 / event.total));
    },
  });
  if (resp.data.code !== 0) throw new Error(resp.data.msg || "音频上传失败");
  return resp.data.data;
}
