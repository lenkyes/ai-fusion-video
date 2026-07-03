export const AUTH_TOKEN_COOKIE_NAME = "auth-token";
export const AUTH_TOKEN_MAX_AGE_SECONDS = 24 * 60 * 60;

function normalizeMaxAge(maxAgeSeconds?: number | null) {
  if (typeof maxAgeSeconds === "number" && Number.isFinite(maxAgeSeconds) && maxAgeSeconds > 0) {
    return Math.floor(maxAgeSeconds);
  }
  return AUTH_TOKEN_MAX_AGE_SECONDS;
}

export function setAuthCookie(accessToken: string, maxAgeSeconds?: number | null) {
  if (typeof document === "undefined") return;
  document.cookie = `${AUTH_TOKEN_COOKIE_NAME}=${accessToken}; path=/; max-age=${normalizeMaxAge(maxAgeSeconds)}; SameSite=Lax`;
}

export function clearAuthCookie() {
  if (typeof document === "undefined") return;
  document.cookie = `${AUTH_TOKEN_COOKIE_NAME}=; path=/; max-age=0`;
}
