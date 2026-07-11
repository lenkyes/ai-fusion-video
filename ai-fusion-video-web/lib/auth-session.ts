import { clearAuthCookie, setAuthCookie } from "@/lib/auth-cookie";
import type { CommonResult, LoginRespVO } from "@/lib/api/types";

const AUTH_STORAGE_KEY = "auth-storage";
const API_BASE_URL =
  (process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:18080").replace(/\/$/, "");

let refreshPromise: Promise<LoginRespVO> | null = null;

export class AuthRefreshError extends Error {
  constructor(message: string, readonly terminal: boolean) {
    super(message);
    this.name = "AuthRefreshError";
  }
}

export function isTerminalAuthError(error: unknown) {
  return error instanceof AuthRefreshError && error.terminal;
}

export function readStoredAuth() {
  if (typeof window === "undefined") return null;
  try {
    const stored = localStorage.getItem(AUTH_STORAGE_KEY);
    return stored ? JSON.parse(stored) : null;
  } catch {
    return null;
  }
}

function persistTokens(result: LoginRespVO) {
  const stored = readStoredAuth();
  if (stored?.state) {
    stored.state.token = result.accessToken;
    stored.state.refreshToken = result.refreshToken;
    localStorage.setItem(AUTH_STORAGE_KEY, JSON.stringify(stored));
  }
  setAuthCookie(result.accessToken, result.expiresIn);
}

export function clearStoredAuth() {
  if (typeof window === "undefined") return;
  localStorage.removeItem(AUTH_STORAGE_KEY);
  clearAuthCookie();
}

export function refreshAuthTokens(): Promise<LoginRespVO> {
  if (refreshPromise) return refreshPromise;

  refreshPromise = (async () => {
    const refreshToken = readStoredAuth()?.state?.refreshToken as string | undefined;
    if (!refreshToken) throw new AuthRefreshError("Missing refresh token", true);

    const response = await fetch(`${API_BASE_URL}/api/auth/refresh`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ refreshToken }),
    });
    const result = (await response.json().catch(() => null)) as CommonResult<LoginRespVO> | null;
    if (!response.ok || result?.code !== 0 || !result.data) {
      const terminal = response.status === 400 || response.status === 401 || result?.code === 401;
      throw new AuthRefreshError(result?.msg || "Refresh token failed", terminal);
    }

    persistTokens(result.data);
    return result.data;
  })().finally(() => {
    refreshPromise = null;
  });

  return refreshPromise;
}
