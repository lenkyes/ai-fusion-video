import { http } from "./client";
export interface ImageTask { id: number; taskId: string; status: number; errorMsg?: string; }
export interface ImageItem { imageUrl?: string; thumbnailUrl?: string; status: number; }
export const imageGenerationApi = { submit: (data: Record<string, unknown>) => http.post<never, string>("/api/generation/image/submit", data), get: (id: string) => http.get<never, ImageTask>(`/api/generation/image/${encodeURIComponent(id)}`), items: (id: number) => http.get<never, ImageItem[]>(`/api/generation/image/${id}/items`) };
