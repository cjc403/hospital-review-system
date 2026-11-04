import { present } from "../presentation";
import { V2ApiError, errorText } from "../v2/api";
import { authHeaders, rejectUnauthorized, sessionToken } from "../auth";

export { V2ApiError as V3ApiError, errorText };

/** v3 接口调用：与 v2 共用认证头约定与错误结构，只换前缀。 */
export async function v3<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = authHeaders(init.headers);
  if (init.body && !(init.body instanceof FormData)) {
    headers.set("Content-Type", "application/json");
  }
  const credential = sessionToken.value;
  let response: Response;
  try {
    response = await fetch(`/api/v3${path}`, { ...init, headers });
  } catch {
    throw new V2ApiError("无法连接本地服务，请确认后端 18080 已启动。", "network_error", 0);
  }
  if (!response.ok) {
    rejectUnauthorized(response.status, credential);
    const body = (await response.json().catch(() => ({}))) as { message?: string; code?: string };
    throw new V2ApiError(body.message || `请求失败（${response.status}）`, body.code || "unknown", response.status);
  }
  return present(await response.json()) as T;
}

/** v3 报告下载：同样带登录会话，出错按契约 message 抛出。 */
export async function v3Download(path: string, fallback: string): Promise<void> {
  const credential = sessionToken.value;
  let response: Response;
  try {
    response = await fetch(`/api/v3${path}`, { headers: authHeaders() });
  } catch {
    throw new V2ApiError("下载连接失败，请检查本地服务。", "network_error", 0);
  }
  if (!response.ok) {
    rejectUnauthorized(response.status, credential);
    const body = (await response.json().catch(() => ({}))) as { message?: string };
    throw new V2ApiError(body.message || "下载失败", "download_failed", response.status);
  }
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = fallback;
  anchor.click();
  window.setTimeout(() => URL.revokeObjectURL(url), 8000);
}
