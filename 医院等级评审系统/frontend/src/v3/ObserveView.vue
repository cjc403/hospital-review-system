<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { errorText, v3 } from "./api";
import { formatValue, formulaLabels, lightBadge, lightLabels, lineagePath } from "./domain";
import type { V3Definition, V3Drilldown, V3Lineage, V3Result } from "./types";
import { notify } from "../store";

const results = ref<V3Result[]>([]);
const definitions = ref<V3Definition[]>([]);
const definitionVersion = ref("");
const filters = ref({ period: "", metricCode: "", deptCode: "" });
const loading = ref(false);
const error = ref("");
const selected = ref<V3Result | null>(null);
const drilldown = ref<V3Drilldown | null>(null);
const lineage = ref<V3Lineage | null>(null);
const showDefinitions = ref(false);

const metrics = computed(() => {
  const seen = new Map<string, string>();
  for (const definition of definitions.value) seen.set(definition.code, definition.name);
  return [...seen.entries()];
});
const summary = computed(() => ({
  red: results.value.filter((item) => item.light === "red").length,
  yellow: results.value.filter((item) => item.light === "yellow").length,
  na: results.value.filter((item) => item.light === "na").length,
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

async function loadResults() {
  loading.value = true;
  error.value = "";
  try {
    const query = new URLSearchParams();
    if (filters.value.period) query.set("period", filters.value.period);
    if (filters.value.metricCode) query.set("metricCode", filters.value.metricCode);
    if (filters.value.deptCode) query.set("deptCode", filters.value.deptCode);
    query.set("limit", "100");
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
  await loadResults();
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
        <span class="metric-label">无数据</span>
        <strong class="metric-number">{{ summary.na }}</strong>
        <span class="metric-foot">缺失或不适用</span>
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
          <select v-model="filters.deptCode" aria-label="科室">
            <option value="">全部科室</option>
            <option value="ALL">全院</option>
            <option value="CARD">心血管内科</option>
            <option value="SURG">普通外科</option>
            <option value="NEUR">神经内科</option>
            <option value="ORTH">骨科</option>
            <option value="RESP">呼吸内科</option>
            <option value="GYNE">妇产科</option>
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

      <div class="table-wrap">
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
        成员明细：{{ drilldown.members.masked ? "脱敏占位（患者级明细需真实接入后按授权开列）" : "已开放" }}
      </p>
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
</style>
