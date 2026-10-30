<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { errorText, v2, v2Download } from "./api";
import type { V2EvidenceItem, V2EvidenceVersion, V2Task } from "./types";
import { notify, role } from "../store";

const items = ref<V2EvidenceItem[]>([]);
const versions = ref<V2EvidenceVersion[]>([]);
const selected = ref<V2EvidenceItem | null>(null);
const tasks = ref<V2Task[]>([]);
const loading = ref(false);
const busy = ref(false);
const error = ref("");
const fileInput = ref<HTMLInputElement | null>(null);

const filter = ref({ ownerType: "task", ownerId: "" });
const form = ref({
  ownerType: "task",
  ownerId: "",
  title: "",
  materialType: "制度文件",
});

const canUpload = computed(() =>
  ["quality", "department"].includes(role.value),
);
const canReview = computed(() => role.value === "quality");
const statusLabels: Record<string, string> = {
  pending: "待审核",
  approved: "已通过",
  rejected: "已退回",
};
const statusBadge: Record<string, string> = {
  pending: "badge-pending",
  approved: "badge-approved",
  rejected: "badge-rejected",
};

async function loadTasks() {
  try {
    const result = await v2<{ items: V2Task[] }>("/tasks");
    tasks.value = result.items;
    if (!form.value.ownerId && result.items[0]) {
      form.value.ownerId = result.items[0].id;
    }
  } catch {
    /* 任务列表失败不影响材料库本身 */
  }
}

async function loadItems() {
  loading.value = true;
  error.value = "";
  try {
    const query = new URLSearchParams();
    if (filter.value.ownerType) query.set("ownerType", filter.value.ownerType);
    if (filter.value.ownerId.trim()) query.set("ownerId", filter.value.ownerId.trim());
    const result = await v2<{ items: V2EvidenceItem[] }>(
      `/evidence?${query.toString()}`,
    );
    items.value = result.items;
    selected.value = null;
    versions.value = [];
  } catch (e) {
    error.value = errorText(e);
  } finally {
    loading.value = false;
  }
}

async function openItem(item: V2EvidenceItem) {
  selected.value = item;
  try {
    const detail = await v2<{ versions: V2EvidenceVersion[] }>(
      `/evidence/${item.id}`,
    );
    versions.value = detail.versions;
  } catch (e) {
    notify(errorText(e), true);
  }
}

async function upload() {
  const file = fileInput.value?.files?.[0];
  if (!file) {
    notify("请选择要上传的材料文件", true);
    return;
  }
  if (!form.value.title.trim()) {
    notify("请填写材料标题", true);
    return;
  }
  if (!form.value.ownerId.trim()) {
    notify("请填写关联对象 ID", true);
    return;
  }
  busy.value = true;
  try {
    const body = new FormData();
    body.append("file", file);
    body.append("ownerType", form.value.ownerType);
    body.append("ownerId", form.value.ownerId.trim());
    body.append("title", form.value.title.trim());
    body.append("materialType", form.value.materialType);
    const result = await v2<{ version: V2EvidenceVersion; idempotent: boolean }>(
      "/evidence",
      { method: "POST", body },
    );
    notify(
      result.idempotent
        ? `内容未变化，沿用版本 v${result.version.versionNo}`
        : `已上传为新版本 v${result.version.versionNo}`,
    );
    form.value.title = "";
    if (fileInput.value) fileInput.value.value = "";
    await loadItems();
  } catch (e) {
    notify(errorText(e), true);
  } finally {
    busy.value = false;
  }
}

async function review(item: V2EvidenceItem, decision: "approved" | "rejected") {
  busy.value = true;
  try {
    await v2(`/evidence/${item.id}/review`, {
      method: "POST",
      body: JSON.stringify({ decision, note: "工作台审核" }),
    });
    notify(decision === "approved" ? "材料已通过" : "材料已退回");
    await loadItems();
  } catch (e) {
    notify(errorText(e), true);
  } finally {
    busy.value = false;
  }
}

async function download(version: V2EvidenceVersion) {
  try {
    await v2Download(
      `/evidence/versions/${version.id}/download`,
      version.fileName,
    );
    notify("已开始下载");
  } catch (e) {
    notify(errorText(e), true);
  }
}

onMounted(async () => {
  await Promise.all([loadItems(), loadTasks()]);
});
</script>

<template>
  <div class="v2-view">
    <div v-if="error" class="error-box">
      {{ error }}
      <button class="text-button" @click="loadItems">重试</button>
    </div>

    <section v-if="canUpload" class="card">
      <div class="section-heading">
        <div>
          <h2>上传材料</h2>
          <p class="hint">
            同一材料同内容重复上传不会产生新版本；内容变化才递增版本，旧版本永久保留。
          </p>
        </div>
      </div>
      <div class="form-grid">
        <label class="field">
          <span>关联类型</span>
          <select v-model="form.ownerType">
            <option value="task">任务</option>
            <option value="clause">条款</option>
            <option value="indicator">指标</option>
            <option value="prereq">前置要求</option>
            <option value="improvement">整改单</option>
          </select>
        </label>
        <label class="field">
          <span>关联对象</span>
          <select v-if="form.ownerType === 'task'" v-model="form.ownerId">
            <option v-for="task in tasks" :key="task.id" :value="task.id">
              {{ task.clauseCode }} · {{ task.deptCode }} · {{ task.status }}
            </option>
          </select>
          <input
            v-else
            v-model="form.ownerId"
            placeholder="对象编号，如 CL-2.1 或 i01"
          />
        </label>
        <label class="field">
          <span>材料标题</span>
          <input v-model="form.title" placeholder="如：心血管内科自查材料" />
        </label>
        <label class="field">
          <span>材料类型</span>
          <input v-model="form.materialType" placeholder="制度文件 / 检查记录" />
        </label>
        <label class="field">
          <span>文件</span>
          <input ref="fileInput" type="file" accept=".pdf,.png,.jpg,.jpeg,.txt,.docx" />
        </label>
      </div>
      <div class="form-actions">
        <button class="button primary" :disabled="busy" @click="upload">
          上传并生成版本
        </button>
        <span class="hint">仅支持 PDF / PNG / JPG / TXT / DOCX，单个不超过 5 MiB</span>
      </div>
    </section>

    <section class="table-card">
      <div class="section-heading">
        <h3>材料清单</h3>
        <div class="inline-filters">
          <select v-model="filter.ownerType" aria-label="按关联类型筛选">
            <option value="">全部类型</option>
            <option value="task">任务</option>
            <option value="clause">条款</option>
            <option value="indicator">指标</option>
            <option value="prereq">前置要求</option>
            <option value="improvement">整改单</option>
          </select>
          <input
            v-model="filter.ownerId"
            placeholder="关联对象 ID"
            aria-label="关联对象 ID"
          />
          <button class="button-small" :disabled="loading" @click="loadItems">
            查询
          </button>
        </div>
      </div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>材料</th>
              <th>关联</th>
              <th>状态</th>
              <th class="numeric">版本数</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in items" :key="item.id" class="clickable-row" @click="openItem(item)">
              <td>
                {{ item.title }}
                <div class="cell-sub">{{ item.materialType }}</div>
              </td>
              <td class="code-label">{{ item.ownerType }} · {{ item.ownerId }}</td>
              <td>
                <span class="badge" :class="statusBadge[item.status]">{{
                  statusLabels[item.status] || item.status
                }}</span>
              </td>
              <td class="numeric">{{ item.versionCount }}</td>
              <td>
                <div class="inline-filters">
                  <button class="button-small" @click.stop="openItem(item)">
                    版本
                  </button>
                  <template v-if="canReview && item.status === 'pending'">
                    <button
                      class="button-small"
                      :disabled="busy"
                      @click.stop="review(item, 'approved')"
                    >
                      通过
                    </button>
                    <button
                      class="button-small"
                      :disabled="busy"
                      @click.stop="review(item, 'rejected')"
                    >
                      退回
                    </button>
                  </template>
                </div>
              </td>
            </tr>
            <tr v-if="!items.length">
              <td colspan="5" class="empty">
                {{ loading ? "正在加载材料…" : "没有符合条件的材料" }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <section v-if="selected" class="table-card">
      <div class="section-heading">
        <h3>{{ selected.title }} · 版本历史</h3>
        <span class="muted">旧版本只读保留</span>
      </div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>版本</th>
              <th>文件</th>
              <th class="numeric">大小</th>
              <th>SHA-256</th>
              <th>上传时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="version in versions" :key="version.id">
              <td class="code-label">v{{ version.versionNo }}</td>
              <td>{{ version.fileName }}</td>
              <td class="numeric">{{ Math.round(version.size / 1024) }} KB</td>
              <td class="code-label">{{ version.sha256.slice(0, 16) }}…</td>
              <td class="muted">{{ version.uploadedAt }}</td>
              <td>
                <button class="button-small" @click="download(version)">
                  下载
                </button>
              </td>
            </tr>
            <tr v-if="!versions.length">
              <td colspan="6" class="empty">该材料暂无版本</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </div>
</template>

<style scoped>
.v2-view {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
</style>
