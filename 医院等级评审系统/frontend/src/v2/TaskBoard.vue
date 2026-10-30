<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { errorText, v2 } from "./api";
import { allowedTaskActions } from "./domain";
import type {
  V2Clause,
  V2Department,
  V2Package,
  V2Task,
  V2TaskStatus,
  V2TaskSummary,
  V2VersionDetail,
} from "./types";
import { notify, role } from "../store";

const tasks = ref<V2Task[]>([]);
const summary = ref<V2TaskSummary | null>(null);
const departments = ref<V2Department[]>([]);
const packages = ref<V2Package[]>([]);
const clauses = ref<V2Clause[]>([]);
const loading = ref(false);
const busy = ref(false);
const error = ref("");
const statusFilter = ref<"ALL" | V2TaskStatus>("ALL");
const returningId = ref("");
const returnReason = ref("");

const form = ref({
  versionId: "",
  deptCode: "",
  clauseCodes: [] as string[],
  dueDate: "2026-11-30",
  priority: "normal" as "high" | "normal" | "low",
  requestKey: "",
});

const canAssign = computed(() => role.value === "quality");
const isDeptView = computed(() => role.value === "department");
const visibleTasks = computed(() =>
  tasks.value.filter(
    (task) => statusFilter.value === "ALL" || task.status === statusFilter.value,
  ),
);
const statusTabs: { key: "ALL" | V2TaskStatus; label: string }[] = [
  { key: "ALL", label: "全部" },
  { key: "todo", label: "待处理" },
  { key: "in_progress", label: "处理中" },
  { key: "submitted", label: "待审核" },
  { key: "returned", label: "已退回" },
  { key: "accepted", label: "已受理" },
];

const statusLabels: Record<string, string> = {
  todo: "待处理",
  in_progress: "处理中",
  submitted: "待审核",
  returned: "已退回",
  accepted: "已受理",
  cancelled: "已取消",
};

const statusBadge: Record<string, string> = {
  todo: "badge-pending",
  in_progress: "badge-doing",
  submitted: "badge-submitted",
  returned: "badge-rejected",
  accepted: "badge-accepted",
  cancelled: "badge-closed",
};

const actionLabels: Record<string, string> = {
  in_progress: "开始处理",
  submitted: "提交",
  accepted: "受理",
  returned: "退回",
  cancelled: "取消",
};

function actionsFor(task: V2Task) {
  return allowedTaskActions(task.status, isDeptView.value);
}

async function loadTasks() {
  loading.value = true;
  error.value = "";
  try {
    const result = await v2<{ items: V2Task[]; summary: V2TaskSummary }>("/tasks");
    tasks.value = result.items;
    summary.value = result.summary;
  } catch (e) {
    error.value = errorText(e);
  } finally {
    loading.value = false;
  }
}

async function loadReference() {
  try {
    const [deptResult, standardResult] = await Promise.all([
      v2<{ items: V2Department[] }>("/departments"),
      v2<{ packages: V2Package[] }>("/standards"),
    ]);
    departments.value = deptResult.items;
    packages.value = standardResult.packages;
    const published = standardResult.packages
      .flatMap((pkg) => pkg.versions)
      .find((ver) => ver.status === "published");
    if (published && !form.value.versionId) form.value.versionId = published.id;
    if (deptResult.items[0] && !form.value.deptCode) {
      form.value.deptCode = deptResult.items[0].code;
    }
  } catch (e) {
    error.value = errorText(e);
  }
}

async function loadClauses(versionId: string) {
  if (!versionId) {
    clauses.value = [];
    return;
  }
  try {
    const detail = await v2<V2VersionDetail>(
      `/standards/versions/${versionId}`,
    );
    clauses.value = detail.clauses;
    form.value.clauseCodes = [];
  } catch (e) {
    error.value = errorText(e);
  }
}

async function assign() {
  if (!form.value.clauseCodes.length) {
    notify("请至少选择一个条款", true);
    return;
  }
  busy.value = true;
  try {
    // 幂等键：同一次提交重复点击只会产生一批任务
    if (!form.value.requestKey) {
      form.value.requestKey = `ui-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    }
    const result = await v2<{ tasks: V2Task[]; idempotent: boolean }>(
      "/tasks/assign",
      {
        method: "POST",
        body: JSON.stringify({
          versionId: form.value.versionId,
          clauseCodes: form.value.clauseCodes,
          deptCode: form.value.deptCode,
          assigneePost: "科室质控员",
          priority: form.value.priority,
          dueDate: form.value.dueDate,
          requestKey: form.value.requestKey,
        }),
      },
    );
    notify(
      result.idempotent
        ? "该请求已处理过，返回原任务"
        : `已分派 ${result.tasks.length} 条条款任务`,
    );
    form.value.clauseCodes = [];
    form.value.requestKey = "";
    await loadTasks();
  } catch (e) {
    notify(errorText(e), true);
  } finally {
    busy.value = false;
  }
}

async function advance(task: V2Task, status: V2TaskStatus) {
  if (status === "returned" && !returnReason.value.trim()) {
    returningId.value = task.id;
    notify("退回必须填写原因", true);
    return;
  }
  busy.value = true;
  try {
    await v2(`/tasks/${task.id}`, {
      method: "PATCH",
      body: JSON.stringify({
        status,
        comment: status === "returned" ? returnReason.value.trim() : "",
      }),
    });
    notify(`任务已${actionLabels[status] || "变更"}`);
    returningId.value = "";
    returnReason.value = "";
    await loadTasks();
  } catch (e) {
    notify(errorText(e), true);
  } finally {
    busy.value = false;
  }
}

watch(
  () => form.value.versionId,
  (id) => loadClauses(id),
);
onMounted(async () => {
  await Promise.all([loadTasks(), loadReference()]);
});
</script>

<template>
  <div class="v2-view">
    <div v-if="error" class="error-box">
      {{ error }}
      <button class="text-button" @click="loadTasks">重试</button>
    </div>

    <section v-if="summary" class="stats-strip">
      <div class="metric-card">
        <span class="metric-label">任务总数</span>
        <strong class="metric-number">{{ summary.total }}</strong>
        <span class="metric-foot">当前可见范围</span>
      </div>
      <div class="metric-card">
        <span class="metric-label">待处理 / 处理中</span>
        <strong class="metric-number"
          >{{ summary.todo }} / {{ summary.in_progress }}</strong
        >
        <span class="metric-foot">按状态机推进</span>
      </div>
      <div class="metric-card">
        <span class="metric-label">待审核</span>
        <strong class="metric-number">{{ summary.submitted }}</strong>
        <span class="metric-foot">评审办受理或退回</span>
      </div>
      <div class="metric-card">
        <span class="metric-label">逾期</span>
        <strong class="metric-number negative">{{ summary.overdue }}</strong>
        <span class="metric-foot">截止日期早于今天</span>
      </div>
    </section>

    <section v-if="canAssign" class="card">
      <div class="section-heading">
        <div>
          <h2>分派条款任务</h2>
          <p class="hint">
            按条款分派到科室；同一幂等键重复提交只产生一批任务。
          </p>
        </div>
      </div>
      <div class="form-grid">
        <label class="field">
          <span>标准版本</span>
          <select v-model="form.versionId">
            <template v-for="pkg in packages" :key="`${pkg.id}-v`">
              <option
                v-for="ver in pkg.versions.filter((v) => v.status === 'published')"
                :key="ver.id"
                :value="ver.id"
              >
                v{{ ver.versionNo }} · {{ pkg.code }}
              </option>
            </template>
          </select>
        </label>
        <label class="field">
          <span>科室</span>
          <select v-model="form.deptCode">
            <option v-for="dept in departments" :key="dept.code" :value="dept.code">
              {{ dept.name }}（{{ dept.code }}）
            </option>
          </select>
        </label>
        <label class="field">
          <span>截止日期</span>
          <input v-model="form.dueDate" type="date" />
        </label>
        <label class="field">
          <span>优先级</span>
          <select v-model="form.priority">
            <option value="high">高</option>
            <option value="normal">普通</option>
            <option value="low">低</option>
          </select>
        </label>
      </div>
      <div class="checkbox-group">
        <label v-for="clause in clauses" :key="clause.code">
          <input v-model="form.clauseCodes" type="checkbox" :value="clause.code" />
          <span>{{ clause.code }} · {{ clause.title }}</span>
        </label>
        <span v-if="!clauses.length" class="muted">该版本暂无条款</span>
      </div>
      <div class="form-actions">
        <button class="button primary" :disabled="busy" @click="assign">
          分派所选条款
        </button>
        <span class="hint">共选择 {{ form.clauseCodes.length }} 条</span>
      </div>
    </section>

    <section class="table-card">
      <div class="section-heading">
        <h3>任务台</h3>
        <div class="inline-filters">
          <span v-if="isDeptView" class="subtle-tag">仅本科室</span>
          <button class="button-small" :disabled="loading" @click="loadTasks">
            刷新
          </button>
        </div>
      </div>
      <div class="tabs page-tabs">
        <button
          v-for="tab in statusTabs"
          :key="tab.key"
          :class="{ active: statusFilter === tab.key }"
          @click="statusFilter = tab.key"
        >
          {{ tab.label }}
        </button>
      </div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>条款</th>
              <th>任务</th>
              <th>科室</th>
              <th>状态</th>
              <th>截止</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="task in visibleTasks" :key="task.id">
              <td class="code-label">{{ task.clauseCode }}</td>
              <td>
                {{ task.title }}
                <div v-if="task.returnReason" class="cell-sub negative">
                  退回原因：{{ task.returnReason }}
                </div>
              </td>
              <td>{{ task.deptCode }}</td>
              <td>
                <span class="badge" :class="statusBadge[task.status]">{{
                  statusLabels[task.status] || task.status
                }}</span>
                <span v-if="task.overdue" class="badge badge-red">逾期</span>
              </td>
              <td class="nowrap">{{ task.dueDate }}</td>
              <td>
                <div class="inline-filters">
                  <template v-for="next in actionsFor(task)" :key="next">
                    <input
                      v-if="next === 'returned' && returningId === task.id"
                      v-model="returnReason"
                      class="return-input"
                      placeholder="退回原因"
                      aria-label="退回原因"
                    />
                    <button
                      class="button-small"
                      :disabled="busy"
                      @click="advance(task, next)"
                    >
                      {{ actionLabels[next] }}
                    </button>
                  </template>
                  <span v-if="!actionsFor(task).length" class="muted">已结束</span>
                </div>
              </td>
            </tr>
            <tr v-if="!visibleTasks.length">
              <td colspan="6" class="empty">
                {{ loading ? "正在加载任务…" : "当前筛选下没有任务" }}
              </td>
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
.checkbox-group {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 8px;
  margin: 12px 0;
}
.checkbox-group label {
  display: flex;
  gap: 7px;
  align-items: center;
  font-size: 12px;
  color: #44546a;
}
.return-input {
  width: 120px;
  min-height: 30px;
  font-size: 11px;
}
</style>
