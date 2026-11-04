<script setup lang="ts">
import { hasPermission } from "../auth";
import { computed, onMounted, ref } from "vue";
import { errorText, v2 } from "./api";
import type { V2Package } from "./types";
import { notify } from "../store";

interface PrereqRow {
  code: string;
  section: string;
  title: string;
  compliance: "compliant" | "non_compliant" | "unknown";
  workflow: "draft" | "submitted" | "accepted" | "rejected";
  statement: string;
  systemGenerated: boolean;
}

interface SampleRow {
  id: string;
  period: string;
  sampleCount: number;
  errorCount: number;
  errorRate: number;
  createdAt: string;
}

const items = ref<PrereqRow[]>([]);
const samples = ref<SampleRow[]>([]);
const versionId = ref("");
const period = ref("2026-10");
const loading = ref(false);
const busy = ref(false);
const error = ref("");
const editing = ref("");
const draft = ref({ compliance: "compliant", workflow: "accepted", statement: "" });

const canEdit = computed(() => hasPermission("assessment.write"));
const nonCompliant = computed(() =>
  items.value.filter((item) => item.compliance === "non_compliant"),
);

const complianceLabels: Record<string, string> = {
  compliant: "符合",
  non_compliant: "不符合",
  unknown: "待确认",
};
const workflowLabels: Record<string, string> = {
  draft: "草稿",
  submitted: "已提交",
  accepted: "已复核",
  rejected: "已退回",
};
const complianceBadge: Record<string, string> = {
  compliant: "badge-green",
  non_compliant: "badge-red",
  unknown: "badge-na",
};

async function loadVersion() {
  try {
    const result = await v2<{ packages: V2Package[] }>("/standards");
    const published = result.packages
      .flatMap((pkg) => pkg.versions)
      .find((ver) => ver.status === "published");
    if (published) versionId.value = published.id;
  } catch (e) {
    error.value = errorText(e);
  }
}

async function loadPrerequisites() {
  loading.value = true;
  error.value = "";
  try {
    const query = versionId.value ? `?versionId=${versionId.value}` : "";
    const result = await v2<{ items: PrereqRow[] }>(`/prerequisites${query}`);
    items.value = result.items;
  } catch (e) {
    error.value = errorText(e);
  } finally {
    loading.value = false;
  }
}

async function loadSamples() {
  try {
    const result = await v2<{ items: SampleRow[] }>("/audit-samples?limit=10");
    samples.value = result.items;
  } catch (e) {
    error.value = errorText(e);
  }
}

function startEdit(item: PrereqRow) {
  editing.value = item.code;
  draft.value = {
    compliance: item.compliance,
    workflow: item.workflow,
    statement: item.statement,
  };
}

async function save(item: PrereqRow) {
  busy.value = true;
  try {
    await v2(`/prerequisites/${item.code}`, {
      method: "PUT",
      body: JSON.stringify({
        versionId: versionId.value,
        compliance: draft.value.compliance,
        workflow: draft.value.workflow,
        statement: draft.value.statement,
      }),
    });
    notify(`${item.code} 结论已保存`);
    editing.value = "";
    await loadPrerequisites();
  } catch (e) {
    notify(errorText(e), true);
  } finally {
    busy.value = false;
  }
}

async function createSample() {
  busy.value = true;
  try {
    const result = await v2<SampleRow>("/audit-samples", {
      method: "POST",
      body: JSON.stringify({ period: period.value }),
    });
    notify(
      `抽查完成：${result.errorCount}/${result.sampleCount} 条错误（${(result.errorRate * 100).toFixed(2)}%）`,
      result.errorRate > 0.1,
    );
    await loadSamples();
  } catch (e) {
    notify(errorText(e), true);
  } finally {
    busy.value = false;
  }
}

onMounted(async () => {
  await loadVersion();
  await Promise.all([loadPrerequisites(), loadSamples()]);
});
</script>

<template>
  <div class="v2-view">
    <div v-if="error" class="error-box">
      {{ error }}
      <button class="text-button" @click="loadPrerequisites">重试</button>
    </div>

    <div v-if="nonCompliant.length" class="warning-strip">
      当前有 {{ nonCompliant.length }} 条前置不符合（{{
        nonCompliant.map((item) => item.code).join("、")
      }}），试算将标记为延期；监测指标符合比例仍照常计算。
    </div>

    <section class="table-card">
      <div class="section-heading">
        <div>
          <h3>前置要求</h3>
          <p class="hint">
            未维护时默认"待确认"，不会默认符合；系统生成的真实性条款不允许人工修改。
          </p>
        </div>
        <button class="button-small" :disabled="loading" @click="loadPrerequisites">
          刷新
        </button>
      </div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>编码</th>
              <th>条款</th>
              <th>结论</th>
              <th>流程</th>
              <th>说明</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <template v-for="item in items" :key="item.code">
              <tr>
                <td class="code-label">{{ item.code }}</td>
                <td>
                  {{ item.title }}
                  <div class="cell-sub">{{ item.section }}</div>
                </td>
                <td>
                  <span class="badge" :class="complianceBadge[item.compliance]">{{
                    complianceLabels[item.compliance]
                  }}</span>
                </td>
                <td>{{ workflowLabels[item.workflow] }}</td>
                <td class="muted">{{ item.statement || "—" }}</td>
                <td>
                  <button
                    v-if="canEdit && !item.systemGenerated"
                    class="button-small"
                    @click="startEdit(item)"
                  >
                    维护
                  </button>
                  <span v-else-if="item.systemGenerated" class="muted">系统生成</span>
                  <span v-else class="muted">只读</span>
                </td>
              </tr>
              <tr v-if="editing === item.code">
                <td colspan="6">
                  <div class="inline-filters">
                    <select v-model="draft.compliance" aria-label="结论">
                      <option value="compliant">符合</option>
                      <option value="non_compliant">不符合</option>
                      <option value="unknown">待确认</option>
                    </select>
                    <select v-model="draft.workflow" aria-label="流程">
                      <option value="draft">草稿</option>
                      <option value="submitted">已提交</option>
                      <option value="accepted">已复核</option>
                      <option value="rejected">已退回</option>
                    </select>
                    <input
                      v-model="draft.statement"
                      class="statement-input"
                      placeholder="结论说明（不符合时建议写明原因）"
                      aria-label="结论说明"
                    />
                    <button class="button primary" :disabled="busy" @click="save(item)">
                      保存
                    </button>
                    <button class="button" :disabled="busy" @click="editing = ''">
                      取消
                    </button>
                  </div>
                </td>
              </tr>
            </template>
            <tr v-if="!items.length">
              <td colspan="6" class="empty">
                {{ loading ? "正在加载前置条款…" : "没有前置条款" }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <section class="card">
      <div class="section-heading">
        <div>
          <h3>条款抽查</h3>
          <p class="hint">
            用最新已应用事实对照标准目标线，偏差 ≥10% 记为错误；错误率严格超过 10% 触发延期。
          </p>
        </div>
        <div class="inline-filters">
          <input v-model="period" placeholder="YYYY-MM" aria-label="抽查周期" />
          <button
            v-if="canEdit"
            class="button primary"
            :disabled="busy"
            @click="createSample"
          >
            发起抽查
          </button>
        </div>
      </div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>周期</th>
              <th class="numeric">抽样</th>
              <th class="numeric">错误</th>
              <th class="numeric">错误率</th>
              <th>时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="sample in samples" :key="sample.id">
              <td>{{ sample.period }}</td>
              <td class="numeric">{{ sample.sampleCount }}</td>
              <td class="numeric">{{ sample.errorCount }}</td>
              <td class="numeric">
                <span
                  class="badge"
                  :class="sample.errorRate > 0.1 ? 'badge-red' : 'badge-green'"
                  >{{ (sample.errorRate * 100).toFixed(2) }}%</span
                >
              </td>
              <td class="muted">{{ sample.createdAt }}</td>
            </tr>
            <tr v-if="!samples.length">
              <td colspan="5" class="empty">还没有抽查记录</td>
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
.statement-input {
  flex: 1;
  min-width: 240px;
}
</style>
