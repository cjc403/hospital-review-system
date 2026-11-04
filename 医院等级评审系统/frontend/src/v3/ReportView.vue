<script setup lang="ts">
import { hasPermission } from "../auth";
import { computed, onMounted, ref } from "vue";
import { errorText, v3, v3Download } from "./api";
import type { V3Definition, V3Report } from "./types";
import { notify, role } from "../store";

const reports = ref<{ id: string; periodFrom: string; periodTo: string; createdAt: string }[]>([]);
const versionId = ref("");
const form = ref({ periodFrom: "2026-07", periodTo: "2026-09" });
const detail = ref<V3Report | null>(null);
const loading = ref(false);
const busy = ref(false);
const error = ref("");

const canWrite = computed(() => hasPermission("report.write"));

async function loadVersion() {
  try {
    const result = await v3<{ versionId: string; items: V3Definition[] }>("/metric-definitions");
    versionId.value = result.versionId;
  } catch (e) {
    error.value = errorText(e);
  }
}

async function loadReports() {
  loading.value = true;
  try {
    const result = await v3<{ items: { id: string; periodFrom: string; periodTo: string; createdAt: string }[] }>(
      "/reports?limit=20",
    );
    reports.value = result.items;
  } catch (e) {
    error.value = errorText(e);
  } finally {
    loading.value = false;
  }
}

async function createReport() {
  busy.value = true;
  try {
    detail.value = await v3<V3Report>("/reports", {
      method: "POST",
      body: JSON.stringify({ versionId: versionId.value, ...form.value }),
    });
    notify("报告快照已生成");
    await loadReports();
  } catch (e) {
    notify(errorText(e), true);
  } finally {
    busy.value = false;
  }
}

async function openReport(id: string) {
  try {
    detail.value = await v3<V3Report>(`/reports/${id}`);
  } catch (e) {
    notify(errorText(e), true);
  }
}

async function download(report: V3Report) {
  try {
    await v3Download(`/reports/${report.id}/document`, `v3-report-${report.id}.docx`);
    notify("报告已开始下载");
  } catch (e) {
    notify(errorText(e), true);
  }
}

onMounted(async () => {
  if (canWrite.value) await loadVersion();
  await loadReports();
});
</script>

<template>
  <div class="v3-view">
    <p v-if="role === 'department'" class="hint">当前账号仅查看本科室数据，全院报告需由院级授权角色查看。</p>
    <div v-if="error" class="error-box">
      {{ error }}
      <button class="text-button" @click="loadReports">重试</button>
    </div>

    <section v-if="canWrite" class="card">
      <div class="section-heading">
        <div>
          <h2>生成报告快照</h2>
          <p class="hint">报告绑定生成时的结果、预警、整改与批次清单；后续数据变化不改写历史报告。</p>
        </div>
      </div>
      <div class="form-grid">
        <label class="field">
          <span>起始周期</span>
          <input v-model="form.periodFrom" placeholder="YYYY-MM" />
        </label>
        <label class="field">
          <span>结束周期</span>
          <input v-model="form.periodTo" placeholder="YYYY-MM" />
        </label>
      </div>
      <div class="form-actions">
        <button class="button primary" :disabled="busy" @click="createReport">生成快照</button>
      </div>
    </section>

    <section class="table-card">
      <div class="section-heading">
        <h3>报告中心</h3>
        <button class="button-small" :disabled="loading" @click="loadReports">刷新</button>
      </div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>报告</th>
              <th>周期</th>
              <th>生成时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="report in reports" :key="report.id" class="clickable-row" @click="openReport(report.id)">
              <td class="code-label">{{ report.id.slice(0, 12) }}</td>
              <td>{{ report.periodFrom }} — {{ report.periodTo }}</td>
              <td class="muted">{{ report.createdAt }}</td>
              <td>
                <button class="button-small" @click.stop="openReport(report.id)">查看</button>
              </td>
            </tr>
            <tr v-if="!reports.length">
              <td colspan="4" class="empty">{{ loading ? "正在加载报告…" : "还没有报告" }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <section v-if="detail" class="card">
      <div class="section-heading">
        <div>
          <h2>报告快照</h2>
          <p class="hint">
            {{ detail.periodFrom }} — {{ detail.periodTo }} · 结果 {{ detail.metricCount }} 条 · 预警
            {{ detail.alertCount }} 条 · 整改 {{ detail.improvementCount }} 项
          </p>
        </div>
        <button class="button" @click="download(detail)">下载 DOCX</button>
      </div>
      <p class="hint">引用批次：{{ detail.batchIds.join("、") || "无" }}</p>
      <p class="hint">引用定义：{{ detail.definitions.join("、") || "无" }}</p>
    </section>
  </div>
</template>

<style scoped>
.v3-view {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
</style>
