import { role } from "../store";

export class V2ApiError extends Error {
  constructor(
    message: string,
    public code: string,
    public status: number,
  ) {
    super(message);
    this.name = "V2ApiError";
  }
}

function headers(init?: RequestInit) {
  const merged = new Headers(init?.headers);
  merged.set("X-Demo-Role", role.value);
  if (init?.body && !(init.body instanceof FormData)) {
    merged.set("Content-Type", "application/json");
  }
  return merged;
}

/** v0.2 接口调用：统一带演示角色，错误按契约错误码抛出。 */
export async function v2<T>(path: string, init: RequestInit = {}): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`/api/v2${path}`, { ...init, headers: headers(init) });
  } catch {
    throw new V2ApiError(
      "无法连接本地服务，请确认后端 18080 已启动。",
      "network_error",
      0,
    );
  }
  if (!response.ok) {
    const body = (await response.json().catch(() => ({}))) as {
      message?: string;
      code?: string;
    };
    throw new V2ApiError(
      body.message || `请求失败（${response.status}）`,
      body.code || "unknown",
      response.status,
    );
  }
  return response.json() as Promise<T>;
}

/** 下载材料或报告，文件名从响应头取，失败同样按契约解析。 */
export async function v2Download(path: string, fallback: string): Promise<void> {
  let response: Response;
  try {
    response = await fetch(`/api/v2${path}`, { headers: headers() });
  } catch {
    throw new V2ApiError("下载连接失败，请检查本地服务。", "network_error", 0);
  }
  if (!response.ok) {
    const body = (await response.json().catch(() => ({}))) as { message?: string };
    throw new V2ApiError(body.message || "下载失败", "download_failed", response.status);
  }
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = fileNameOf(response, fallback);
  anchor.click();
  window.setTimeout(() => URL.revokeObjectURL(url), 8000);
}

function fileNameOf(response: Response, fallback: string): string {
  const disposition = response.headers.get("Content-Disposition") || "";
  const utf8 = /filename\*=UTF-8''([^;]+)/i.exec(disposition);
  if (utf8?.[1]) return decodeURIComponent(utf8[1]);
  const plain = /filename="?([^";]+)"?/i.exec(disposition);
  return plain?.[1] ? decodeURIComponent(plain[1]) : fallback;
}

export function errorText(error: unknown): string {
  if (error instanceof V2ApiError) return error.message;
  return error instanceof Error ? error.message : "操作失败";
}
