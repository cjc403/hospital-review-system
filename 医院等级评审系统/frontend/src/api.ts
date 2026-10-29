import type { Role } from "./types";

export class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

export async function request<T>(
  path: string,
  role: Role,
  options: RequestInit = {},
): Promise<T> {
  const headers = new Headers(options.headers);
  headers.set("X-Demo-Role", role);
  if (options.body && !(options.body instanceof FormData))
    headers.set("Content-Type", "application/json");
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
    let message = `请求失败（${response.status}）`;
    try {
      message = (await response.json()).message || message;
    } catch {
      /* non JSON proxy response */
    }
    throw new ApiError(message, response.status);
  }
  return response.json() as Promise<T>;
}

export async function downloadFile(
  path: string,
  role: Role,
  fallbackName: string,
): Promise<void> {
  let response: Response;
  try {
    response = await fetch(`/api${path}`, { headers: { "X-Demo-Role": role } });
  } catch {
    throw new ApiError("下载连接失败，请检查本地服务。", 0);
  }
  if (!response.ok) {
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
