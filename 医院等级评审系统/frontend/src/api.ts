import { present, displayText } from "./presentation";
import { authHeaders, rejectUnauthorized, sessionToken } from "./auth";
import type { Role } from "./types";

export class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
  ) {
    super(displayText(message));
    this.name = "ApiError";
  }
}

/** Legacy role argument is retained for callers; only the server session authorizes access. */
export async function request<T>(
  path: string,
  _legacyRole: Role,
  options: RequestInit = {},
): Promise<T> {
  const headers = authHeaders(options.headers);
  if (options.body && !(options.body instanceof FormData))
    headers.set("Content-Type", "application/json");
  const credential = sessionToken.value;
  let response: Response;
  try {
    response = await fetch(`/api${path}`, { ...options, headers });
  } catch {
    throw new ApiError(
      "无法连接本地服务，请检查后端是否已启动（18080 端口）。",
      0,
    );
  }
  if (!response.ok) {
    rejectUnauthorized(response.status, credential);
    let message = `请求失败（${response.status}）`;
    try {
      message = (await response.json()).message || message;
    } catch {
      /* non JSON proxy response */
    }
    throw new ApiError(message, response.status);
  }
  return present(await response.json()) as T;
}

export async function downloadFile(
  path: string,
  _legacyRole: Role,
  fallbackName: string,
): Promise<void> {
  const credential = sessionToken.value;
  let response: Response;
  try {
    response = await fetch(`/api${path}`, { headers: authHeaders() });
  } catch {
    throw new ApiError("下载连接失败，请检查本地服务。", 0);
  }
  if (!response.ok) {
    rejectUnauthorized(response.status, credential);
    const body = await response.json().catch(() => ({}));
    throw new ApiError(body.message || "文件下载失败", response.status);
  }
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = fallbackName;
  a.click();
  window.setTimeout(() => URL.revokeObjectURL(url), 10000);
}
