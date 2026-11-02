<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { errorText, v3 } from "./api";
import { alertActionLabels, alertActions, alertTypeLabels } from "./domain";
import type { V3Alert, V3Improvement } from "./types";
import { notify, role } from "../store";

const alerts = ref<V3Alert[]>([]);
const improvements = ref<V3Improvement[]>([]);
const statusFilter = ref("");
const loading = ref(false);
const busy = ref(false);
const error = ref("");
const assigning = ref("");
const assignDraft = ref({ assignee: "", dueDate: "2026-12-31", note: "" });
const improving = ref("");
const improveDraft = ref({ rootCause: "", action: "", owner: "", dueDate: "2026-12-31" });
const reviewing = ref("");
const reviewDraft = ref({ conclusion: "partially_improved", note: "", evidenceItemId: "" });

const canWrite = computed(() => role.value === "quality");
const canHandleAlert = computed(() => role.value === "quality" || role.value === "department");
const statusLabels: Record<string, string> = {
  open: "待确认",
  acknowledged: "已确认",
  assigned: "已分派",
  resolved: "已解决",
  ignored: "已忽略",
};
const statusBadge: Record<string, string> = {
  open: "badge-pending",
  acknowledged: "badge-doing",
  assigned: "badge-assigned",
  resolved: "badge-approved",
  ignored: "badge-closed",
};

async function loadAlerts() {
  loading.value = true;
  error.value = "";
  try {
    const query = statusFilter.value ? `?status=${statusFilter.value}` : "";
    const result = await v3<{ items: V3Alert[] }>(`/alerts${query}`);
    alerts.value = result.items;
  } catch (e) {
    error.value = errorText(e);
  } finally {
    loading.value = false;
  }
}

async function loadImprovements() {
  try {
    const result = await v3<{ items: V3Improvement[] }>("/improvements");
    improvements.value = result.items;
  } catch (e) {
    error.value = errorText(e);
  }
}

function visibleAlertActions(status: V3Alert["status"]): string[] {
  const actions = alertActions(status);
  return role.value === "department" ? actions.filter((action) => action === "ack") : actions;
}

async function act(alert: V3Alert, action: string) {
  if (action === "assign" && assigning.value !== alert.id) {
    assigning.value = alert.id;
    assignDraft.value.assignee = alert.assignee || "";
    return;
  }
  busy.value = true;
  try {
    const body =
      action === "assign"
        ? JSON.stringify(assignDraft.value)
        : JSON.stringify({ note: `${alertActionLabels[action]}（工作台）` });
    await v3(`/alerts/${alert.id}/${action}`, { method: "POST", body });
    notify(`预警已${alertActionLabels[action]}`);
    assigning.value = "";
    await loadAlerts();
  } catch (e) {
    notify(errorText(e), true);
  } finally {
    busy.value = false;
  }
}

async function createImprovement(alert: V3Alert) {
  busy.value = true;
  try {
    await v3("/improvements/from-alert", {
      method: "POST",
      body: JSON.stringify({ alertId: alert.id, ...improveDraft.value }),
    });
    notify("已由预警创建整改项目");
    improving.value = "";
    await Promise.all([loadAlerts(), loadImprovements()]);
  } catch (e) {
    notify(errorText(e), true);
  } finally {
    busy.value = false;
  }
}

async function review(improvement: V3Improvement) {
  if (!reviewDraft.value.evidenceItemId.trim()) {
    notify("关闭整改必须填写复核证据材料 ID（可先在 v0.2 材料库上传）", true);
    return;
  }
  busy.value = true;
  try {
    await v3(`/improvements/${improvement.id}/review`, {
      method: "POST",
      body: JSON.stringify(reviewDraft.value),
    });
    notify("整改已复核关闭");
    reviewing.value = "";
    await loadImprovements();
  } catch (e) {
    notify(errorText(e), true);
  } finally {
    busy.value = false;
  }
}

onMounted(async () => {
  await Promise.all([loadAlerts(), loadImprovements()]);
});
</script>

<template>
  <div class="v3-view">
    <div v-if="error" class="error-box">
      {{ error }}
      <button class="text-button" @click="loadAlerts">重试</button>
    </div>

    <section class="table-card">
      <div class="section-heading">
        <h3>预警</h3>
        <div class="inline-filters">
          <select v-model="statusFilter" aria-label="状态筛选" @change="loadAlerts">
            <option value="">全部状态</option>
            <option value="open">待确认</option>
            <option value="acknowledged">已确认</option>
            <option value="assigned">已分派</option>
            <option value="resolved">已解决</option>
            <option value="ignored">已忽略</option>
          </select>
          <button class="button-small" :disabled="loading" @click="loadAlerts">刷新</button>
        </div>
      </div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>指标</th>
              <th>科室</th>
              <th>周期</th>
              <th>类型</th>
              <th>严重度</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <template v-for="alert in alerts" :key="alert.id">
              <tr>
                <td>
                  {{ alert.metricName }}
                  <div class="cell-sub muted">{{ alert.detail }}</div>
                </td>
                <td>{{ alert.deptCode }}</td>
                <td class="nowrap">{{ alert.period }}</td>
                <td>{{ alertTypeLabels[alert.type] || alert.type }}</td>
                <td>
                  <span class="badge" :class="alert.severity === 'high' ? 'badge-red' : 'badge-yellow'">
                    {{ alert.severity === "high" ? "高" : "中" }}
                  </span>
                </td>
                <td>
                  <span class="badge" :class="statusBadge[alert.status]">{{ statusLabels[alert.status] }}</span>
                </td>
                <td>
                  <div class="inline-filters">
                    <template v-for="action in visibleAlertActions(alert.status)" :key="action">
                      <button class="button-small" :disabled="busy || !canHandleAlert" @click="act(alert, action)">
                        {{ alertActionLabels[action] }}
                      </button>
                    </template>
                    <button
                      v-if="canWrite && alert.status !== 'resolved' && alert.status !== 'ignored'"
                      class="button-small"
                      @click="improving = improving === alert.id ? '' : alert.id"
                    >
                      建整改
                    </button>
                  </div>
                </td>
              </tr>
              <tr v-if="assigning === alert.id">
                <td colspan="7">
                  <div class="inline-filters">
                    <input v-model="assignDraft.assignee" placeholder="责任人" aria-label="责任人" />
                    <input v-model="assignDraft.dueDate" placeholder="YYYY-MM-DD" aria-label="期限" />
                    <input v-model="assignDraft.note" placeholder="分派说明" aria-label="分派说明" />
                    <button class="button primary" :disabled="busy" @click="act(alert, 'assign')">确认分派</button>
                    <button class="button" @click="assigning = ''">取消</button>
                  </div>
                </td>
              </tr>
              <tr v-if="improving === alert.id">
                <td colspan="7">
                  <div class="form-grid">
                    <label class="field"><span>根因</span><input v-model="improveDraft.rootCause" /></label>
                    <label class="field"><span>措施</span><input v-model="improveDraft.action" /></label>
                    <label class="field"><span>负责人</span><input v-model="improveDraft.owner" /></label>
                    <label class="field"><span>截止</span><input v-model="improveDraft.dueDate" /></label>
                  </div>
                  <div class="form-actions">
                    <button class="button primary" :disabled="busy" @click="createImprovement(alert)">
                      创建整改
                    </button>
                    <button class="button" @click="improving = ''">取消</button>
                  </div>
                </td>
              </tr>
            </template>
            <tr v-if="!alerts.length">
              <td colspan="7" class="empty">{{ loading ? "正在加载预警…" : "没有符合条件的预警" }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <section class="table-card">
      <div class="section-heading">
        <h3>整改（PDCA）</h3>
        <button class="button-small" @click="loadImprovements">刷新</button>
      </div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>指标</th>
              <th>根因 / 措施</th>
              <th>负责人</th>
              <th>状态</th>
              <th>复核结论</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <template v-for="improvement in improvements" :key="improvement.id">
              <tr>
                <td>{{ improvement.metricName }}</td>
                <td>
                  {{ improvement.rootCause }}
                  <div class="cell-sub muted">{{ improvement.action }}</div>
                </td>
                <td>{{ improvement.owner }}</td>
                <td>
                  <span class="badge" :class="improvement.status === 'closed' ? 'badge-approved' : 'badge-doing'">
                    {{ improvement.status === "closed" ? "已关闭" : "整改中" }}
                  </span>
                </td>
                <td>
                  {{ improvement.reviewConclusion === 'improved' ? '已改善' : improvement.reviewConclusion === 'partially_improved' ? '部分改善' : improvement.reviewConclusion === 'not_recovered' ? '未恢复' : improvement.reviewConclusion === 'invalidated' ? '问题不成立' : '—' }}
                  <div v-if="improvement.baselineValue !== null" class="cell-sub muted">
                    基线 {{ improvement.baselineValue }} → 最新 {{ improvement.latestValue ?? "—" }}
                    <span v-if="improvement.targetValue !== null"> / 目标 {{ improvement.targetValue }}</span>
                  </div>
                </td>
                <td>
                  <button
                    v-if="canWrite && improvement.status !== 'closed'"
                    class="button-small"
                    @click="reviewing = reviewing === improvement.id ? '' : improvement.id"
                  >
                    复核
                  </button>
                  <span v-else class="muted">—</span>
                </td>
              </tr>
              <tr v-if="reviewing === improvement.id">
                <td colspan="6">
                  <div class="inline-filters">
                    <select v-model="reviewDraft.conclusion" aria-label="复核结论">
                      <option value="improved">已改善</option>
                      <option value="partially_improved">部分改善</option>
                      <option value="not_recovered">未恢复</option>
                      <option value="invalidated">问题不成立</option>
                    </select>
                    <input v-model="reviewDraft.note" placeholder="复核说明" aria-label="复核说明" />
                    <input
                      v-model="reviewDraft.evidenceItemId"
                      placeholder="复核证据材料 ID（必填）"
                      aria-label="复核证据材料 ID"
                    />
                    <button class="button primary" :disabled="busy" @click="review(improvement)">提交复核</button>
                    <button class="button" @click="reviewing = ''">取消</button>
                  </div>
                </td>
              </tr>
            </template>
            <tr v-if="!improvements.length">
              <td colspan="6" class="empty">还没有整改项目</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </div>
</template>

<style scoped>
.v3-view {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.table-wrap { max-height: 480px; overflow: auto; }
thead { position: sticky; top: 0; z-index: 1; background: var(--surface, #fff); }
</style>
