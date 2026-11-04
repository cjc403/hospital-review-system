import { currentUser, hasPermission, onSessionCleared } from "./auth";
import { computed, ref, watch } from "vue";
import { downloadFile, request } from "./api";
import type { Role, Workspace } from "./types";

export const role = ref<Role>("quality");
export const workspace = ref<Workspace | null>(null);
export const loading = ref(false);
export const busy = ref(false);
export const loadError = ref("");
export const notice = ref<{ message: string; error: boolean } | null>(null);
export const canWrite = computed(() => hasPermission("legacy.write"));
export const roleLabels: Record<Role, string> = {
  quality: "质控管理员",
  leader: "院级领导",
  department: "科室负责人",
  information: "信息科",
};
let generation = 0;
onSessionCleared(() => { generation++; workspace.value = null; notice.value = null; loadError.value = ""; loading.value = false; });
watch(currentUser, user => { if (user) role.value = user.legacyRole; }, { immediate: true });
let toastTimer: ReturnType<typeof setTimeout>;

export function notify(message: string, error = false) {
  notice.value = { message, error };
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => (notice.value = null), error ? 8000 : 4500);
}

export async function loadWorkspace() {
  if (!currentUser.value) return;
  const current = ++generation;
  loading.value = true;
  loadError.value = "";
  try {
    const result = await request<Workspace>("/workspace", role.value);
    if (current === generation) workspace.value = result;
  } catch (e) {
    if (current === generation) loadError.value = (e as Error).message;
  } finally {
    if (current === generation) loading.value = false;
  }
}

export async function mutate(
  path: string,
  body: unknown = {},
  method = "POST",
  message = "操作已保存",
): Promise<boolean> {
  if (busy.value) return false;
  busy.value = true;
  try {
    await request(path, role.value, {
      method,
      body: body instanceof FormData ? body : JSON.stringify(body),
    });
    await loadWorkspace();
    notify(
      loadError.value ? `${message}；刷新失败，请点击刷新重试。` : message,
      !!loadError.value,
    );
    return true;
  } catch (e) {
    notify((e as Error).message, true);
    return false;
  } finally {
    busy.value = false;
  }
}

export async function download(path: string, name: string) {
  if (busy.value) return;
  busy.value = true;
  try {
    await downloadFile(path, role.value, name);
    notify("文件已下载");
  } catch (e) {
    notify((e as Error).message, true);
  } finally {
    busy.value = false;
  }
}

export function reopenImprovement(id: string) {
  return mutate(`/improvements/${id}`, { status: "reopen" }, "PATCH", "改进单已重新打开");
}

export async function uploadEvidence(
  file: File,
  ownerType: "prereq" | "improvement",
  ownerId: string,
) {
  if (!/\.(pdf|png|jpe?g|txt|docx)$/i.test(file.name)) {
    notify("仅支持 PDF、PNG、JPG、TXT、DOCX 文件", true);
    return false;
  }
  if (file.size > 5 * 1024 * 1024 || file.size === 0) {
    notify("文件须大于 0 且不超过 5 MiB", true);
    return false;
  }
  const form = new FormData();
  form.append("file", file);
  form.append("ownerType", ownerType);
  form.append("ownerId", ownerId);
  return mutate("/evidence", form, "POST", "佐证文件已上传并绑定");
}
