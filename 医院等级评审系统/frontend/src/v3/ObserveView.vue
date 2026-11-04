<script setup lang="ts">
import { hasPermission, currentUser } from "../auth";
import { computed, onMounted, ref } from "vue";
import { errorText, v3 } from "./api";
import { formatValue, formulaLabels, lightBadge, lightLabels, lineagePath } from "./domain";
import type { V3Alert, V3Definition, V3Drilldown, V3Lineage, V3Result } from "./types";
import { notify, workspace } from "../store";

const results = ref<V3Result[]>([]);
const definitions = ref<V3Definition[]>([]);
const definitionVersion = ref("");
const filters = ref({ period: workspace.value?.meta.period || "2026-09", metricCode: "", deptCode: currentUser.value?.deptCode || "", chapterCode: "" });
const loading = ref(false);
const error = ref("");
const selected = ref<V3Result | null>(null);
const busy = ref(false);
const drilldown = ref<V3Drilldown | null>(null);
const lineage = ref<V3Lineage | null>(null);
const showDefinitions = ref(false);
const alerts = ref<V3Alert[]>([]);
const improving = ref(false);
const improveDraft = ref({ rootCause: "", action: "", owner: "", dueDate: "2026-12-31" });
const canWrite = computed(() => hasPermission("improvement.write"));

const metrics = computed(() => {
  const seen = new Map<string, string>();
  for (const definition of definitions.value) seen.set(definition.code, definition.name);
  return [...seen.entries()];
});
const chapters = ["CH1", "CH2", "CH3", "CH4", "CH5"];

const summary = computed(() => ({
  red: results.value.filter((item) => item.light === "red").length,
  yellow: results.value.filter((item) => item.light === "yellow").length,
  na: results.value.filter((item) => item.light === "na").length,
  periods: new Set(results.value.map((item) => item.period)).size,
  openAlerts: alerts.value.filter((alert) => alert.status !== "resolved" && alert.status !== "ignored").length,
}));

async function loadDefinitions() {
  try {
    const result = await v3<{ versionId: string; items: V3Definition[] }>("/metric-definitions");
    definitions.value = result.items;
    definitionVersion.value = result.versionId;
  } catch (e) {
    error.value = errorText(e);
  }
}

async function loadAlerts() {
  if (!hasPermission("alert.read")) return;
  try {
    const result = await v3<{ items: V3Alert[] }>("/alerts?limit=100");
    alerts.value = result.items;
  } catch {
    /* 预警加载失败不影响结果视图 */
  }
}

async function createImprovement() {
  if (!selected.value) return;
  busy.value = true;
  try {
    await v3("/improvements", {
      method: "POST",
      body: JSON.stringify({
        sourceType: "result",
        sourceId: selected.value.id,
        ...improveDraft.value,
      }),
    });
    notify("已由该指标结果创建整改项目");
    improving.value = false;
  } catch (e) {
    notify(errorText(e), true);
  } finally {
    busy.value = false;
  }
}

async function loadResults() {
  loading.value = true;
  error.value = "";
  try {
    const query = new URLSearchParams();
    if (filters.value.period) query.set("period", filters.value.period);
    if (filters.value.metricCode) query.set("metricCode", filters.value.metricCode);
    if (filters.value.deptCode) query.set("deptCode", filters.value.deptCode);
    if (filters.value.chapterCode) query.set("chapterCode", filters.value.chapterCode);
    query.set("limit", "200");
    const result = await v3<{ items: V3Result[] }>(`/metric-results?${query.toString()}`);
    results.value = result.items;
    selected.value = null;
    drilldown.value = null;
    lineage.value = null;
  } catch (e) {
    error.value = errorText(e);
  } finally {
    loading.value = false;
  }
}

async function openResult(result: V3Result) {
  selected.value = result;
  try {
    const [detail, graph] = await Promise.all([
      v3<V3Drilldown>(`/metric-results/${result.id}/drilldown`),
      v3<V3Lineage>(`/lineage/results/${result.id}`),
    ]);
    drilldown.value = detail;
    lineage.value = graph;
  } catch (e) {
    notify(errorText(e), true);
  }
}

onMounted(async () => {
  await loadDefinitions();
  await Promise.all([loadResults(), loadAlerts()]);
});
</script>

<template>
  <div class="v3-view">
    <div v-if="error" class="error-box">
      {{ error }}
      <button class="text-button" @click="loadResults">重试</button>
    </div>

    <section class="stats-strip">
      <div class="metric-card">
        <span class="metric-label">结果条数</span>
        <strong class="metric-number">{{ results.length }}</strong>
        <span class="metric-foot">当前筛选范围</span>
      </div>
      <div class="metric-card">
        <span class="metric-label">红灯</span>
        <strong class="metric-number negative">{{ summary.red }}</strong>
        <span class="metric-foot">超阈值，需处置</span>
      </div>
      <div class="metric-card">
        <span class="metric-label">黄灯</span>
        <strong class="metric-number">{{ summary.yellow }}</strong>
        <span class="metric-foot">预警区间</span>
      </div>
      <div class="metric-card">
        <span class="metric-label">覆盖周期 / 待办预警</span>
        <strong class="metric-number">{{ summary.periods }} / {{ summary.openAlerts }}</strong>
        <span class="metric-foot">无数据 {{ summary.na }} 条；预警含未确认与整改中</span>
      </div>
    </section>

    <section class="table-card">
      <div class="section-heading">
        <h3>指标监测</h3>
        <div class="inline-filters">
          <input v-model="filters.period" placeholder="周期 YYYY-MM" aria-label="周期" />
          <select v-model="filters.metricCode" aria-label="指标">
            <option value="">全部指标</option>
            <option v-for="[code, name] in metrics" :key="code" :value="code">{{ code }} · {{ name }}</option>
          </select>
          <select v-model="filters.deptCode" aria-label="科室" :disabled="!!currentUser?.deptCode">
            <option v-if="currentUser?.deptCode" :value="currentUser.deptCode">{{ currentUser.deptName }}</option>
            <template v-else>
            <option value="">全部科室</option>
            <option value="ALL">全院</option>
            <option value="CARD">心血管内科</option>
            <option value="SURG">普通外科</option>
            <option value="NEUR">神经内科</option>
            <option value="ORTH">骨科</option>
            <option value="RESP">呼吸内科</option>
            <option value="GYNE">妇产科</option>
            </template>
          </select>
          <select v-model="filters.chapterCode" aria-label="章节">
            <option value="">全部章节</option>
            <option v-for="code in chapters" :key="code" :value="code">{{ code }}</option>
          </select>
          <button class="button-small" :disabled="loading" @click="loadResults">查询</button>
          <button class="button-small" @click="showDefinitions = !showDefinitions">
            {{ showDefinitions ? "收起指标库" : "指标库" }}
          </button>
        </div>
      </div>

      <div v-if="showDefinitions" class="table-wrap definition-block">
        <table>
          <thead>
            <tr>
              <th>编码</th>
              <th>指标</th>
              <th>公式</th>
              <th>方向</th>
              <th class="numeric">目标 / 预警</th>
              <th class="numeric">版本</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="definition in definitions" :key="definition.id">
              <td class="code-label">{{ definition.code }}</td>
              <td>{{ definition.name }}</td>
              <td>{{ formulaLabels[definition.formulaType] || definition.formulaType }}</td>
              <td>{{ definition.direction === "high" ? "高优" : "低优" }}</td>
              <td class="numeric">{{ definition.target ?? "—" }} / {{ definition.warning ?? "—" }}</td>
              <td class="numeric">v{{ definition.versionNo }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="table-wrap results-scroll">
        <table>
          <thead>
            <tr>
              <th>周期</th>
              <th>指标</th>
              <th>科室</th>
              <th class="numeric">值</th>
              <th class="numeric">分子 / 分母</th>
              <th>灯号</th>
              <th>公式</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="result in results" :key="result.id" class="clickable-row" @click="openResult(result)">
              <td class="nowrap">{{ result.period }}</td>
              <td>{{ result.metricName }}</td>
              <td>{{ result.deptCode }}</td>
              <td class="numeric">{{ formatValue(result.value, result.unit) }}</td>
              <td class="numeric muted">
                {{ result.numerator ?? "—" }} / {{ result.denominator ?? "—" }}
              </td>
              <td>
                <span class="badge" :class="lightBadge[result.light]">{{ lightLabels[result.light] }}</span>
              </td>
              <td class="muted">{{ formulaLabels[result.formulaType] || result.formulaType }}</td>
            </tr>
            <tr v-if="!results.length">
              <td colspan="7" class="empty">{{ loading ? "正在加载结果…" : "当前筛选没有结果" }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <section v-if="selected && drilldown" class="card">
      <div class="section-heading">
        <div>
          <h2>{{ selected.metricName }} · {{ selected.period }} · {{ selected.deptCode }}</h2>
          <p class="hint">
            值 {{ formatValue(selected.value, selected.unit) }} · 目标 {{ selected.target ?? "—" }} ·
            预警线 {{ selected.warning ?? "—" }} · 原因码 {{ selected.reasonCode }} · 定义 v{{ selected.definitionVersion }}
          </p>
        </div>
      </div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>科室</th>
              <th class="numeric">值</th>
              <th class="numeric">分子 / 分母</th>
              <th>灯号</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="dept in drilldown.departments" :key="dept.deptCode">
              <td>{{ dept.deptCode }}</td>
              <td class="numeric">{{ formatValue(dept.value, selected.unit) }}</td>
              <td class="numeric muted">{{ dept.numerator ?? "—" }} / {{ dept.denominator ?? "—" }}</td>
              <td>
                <span class="badge" :class="lightBadge[dept.light]">{{ lightLabels[dept.light] || dept.light }}</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p class="hint">
        {{ drilldown.members.note || "当前角色不展示成员样本" }}
      </p>
      <div v-if="hasPermission('member.read') && drilldown.members.items.length" class="table-wrap results-scroll">
        <table>
          <thead><tr><th>标识</th><th>归期日期</th><th>科室</th><th>样本角色</th></tr></thead>
          <tbody><tr v-for="member in drilldown.members.items" :key="member.inpatientNo">
            <td>{{ member.inpatientNo }}</td><td>{{ member.dischargeDate }}</td>
            <td>{{ member.deptCode }}</td><td>分母成员样本</td>
          </tr></tbody>
        </table>
        <p class="hint">共 {{ drilldown.members.total }} 个分母样本，展示最多 20 条。</p>
      </div>
      <div v-if="canWrite" class="inline-filters">
        <button class="button-small" @click="improving = !improving">
          {{ improving ? "取消建整改" : "由该结果建整改" }}
        </button>
      </div>
      <div v-if="improving" class="form-grid">
        <label class="field"><span>根因</span><input v-model="improveDraft.rootCause" /></label>
        <label class="field"><span>措施</span><input v-model="improveDraft.action" /></label>
        <label class="field"><span>负责人</span><input v-model="improveDraft.owner" /></label>
        <label class="field"><span>截止</span><input v-model="improveDraft.dueDate" /></label>
      </div>
      <div v-if="improving" class="form-actions">
        <button class="button primary" :disabled="busy" @click="createImprovement">创建整改</button>
      </div>
      <div v-if="lineage" class="info-strip">
        <strong>血缘：</strong>{{ lineagePath(lineage.nodes) }}
        <div class="hint">节点 {{ lineage.nodes.length }} 个，边 {{ lineage.edges.length }} 条</div>
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
.definition-block {
  border-bottom: 1px solid var(--border);
  margin-bottom: 8px;
}
.results-scroll { max-height: 520px; overflow: auto; }
</style>
