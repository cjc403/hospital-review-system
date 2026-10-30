<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { errorText, v2 } from "./api";
import { parseDataRows } from "./domain";
import type { V2Batch, V2BatchDetail } from "./types";
import { notify, role } from "../store";

const sources = ref<{ code: string; name: string; kind: string }[]>([]);
const batches = ref<V2Batch[]>([]);
const detail = ref<V2BatchDetail | null>(null);
const loading = ref(false);
const busy = ref(false);
const error = ref("");

const form = ref({
  sourceCode: "CSV-IMPORT",
  period: "2026-12",
  csv: "i01,960,2000,0.48\ni02,1140,2000,57",
});

const canWrite = computed(() => role.value === "quality");

async function loadSources() {
  try {
    const result = await v2<{ items: { code: string; name: string; kind: string }[] }>(
      "/data-sources",
    );
    sources.value = result.items;
    if (result.items[0]) form.value.sourceCode = result.items[0].code;
  } catch (e) {
    error.value = errorText(e);
  }
}

async function loadBatches() {
  loading.value = true;
  try {
    const result = await v2<{ items: V2Batch[] }>("/batches?limit=30");
    batches.value = result.items;
  } catch (e) {
    error.value = errorText(e);
  } finally {
    loading.value = false;
  }
}

async function importBatch() {
  const rows = parseDataRows(form.value.csv);
  if (!rows.length) {
    notify("请按“指标编码,分子,分母,值”每行一条填写", true);
    return;
  }
  busy.value = true;
  try {
    detail.value = await v2<V2BatchDetail>("/batches", {
      method: "POST",
      body: JSON.stringify({
        sourceCode: form.value.sourceCode,
        period: form.value.period,
        rows,
      }),
    });
    notify(
      detail.value.batch.status === "validated"
        ? "批次通过质量校验，可以应用"
        : "批次存在质量错误，已被阻断",
      detail.value.batch.status !== "validated",
    );
    await loadBatches();
  } catch (e) {
    notify(errorText(e), true);
  } finally {
    busy.value = false;
  }
}

async function openBatch(batch: V2Batch) {
  try {
    detail.value = await v2<V2BatchDetail>(`/batches/${batch.id}`);
  } catch (e) {
    notify(errorText(e), true);
  }
}

async function apply() {
  if (!detail.value) return;
  busy.value = true;
  try {
    detail.value = await v2<V2BatchDetail>(
      `/batches/${detail.value.batch.id}/apply`,
      { method: "POST", body: "{}" },
    );
    notify("批次已应用，可进入试算");
    await loadBatches();
  } catch (e) {
    notify(errorText(e), true);
  } finally {
    busy.value = false;
  }
}

async function replay() {
  if (!detail.value) return;
  busy.value = true;
  try {
    detail.value = await v2<V2BatchDetail>(
      `/batches/${detail.value.batch.id}/replay`,
      { method: "POST", body: "{}" },
    );
    notify("已按原批次重放，重新走质量校验");
    await loadBatches();
  } catch (e) {
    notify(errorText(e), true);
  } finally {
    busy.value = false;
  }
}

onMounted(async () => {
  await Promise.all([loadSources(), loadBatches()]);
});
</script>

<template>
  <div class="v2-view">
    <div v-if="error" class="error-box">
      {{ error }}
      <button class="text-button" @click="loadBatches">重试</button>
    </div>

    <section v-if="canWrite" class="card">
      <div class="section-heading">
        <div>
          <h2>导入数据批次</h2>
          <p class="hint">
            每行“指标编码,分子,分母,值”。有质量错误的批次会被阻断，不能应用也不能进入试算。
          </p>
        </div>
      </div>
      <div class="form-grid">
        <label class="field">
          <span>数据源</span>
          <select v-model="form.sourceCode">
            <option v-for="source in sources" :key="source.code" :value="source.code">
              {{ source.name }}（{{ source.kind }}）
            </option>
          </select>
        </label>
        <label class="field">
          <span>周期</span>
          <input v-model="form.period" placeholder="YYYY-MM" />
        </label>
      </div>
      <label class="field">
        <span>数据行</span>
        <textarea v-model="form.csv" rows="6" spellcheck="false"></textarea>
      </label>
      <div class="form-actions">
        <button class="button primary" :disabled="busy" @click="importBatch">
          导入并校验
        </button>
        <span class="hint">非比例指标只填“编码,,,值”</span>
      </div>
    </section>

    <section class="table-card">
      <div class="section-heading">
        <h3>批次列表</h3>
        <button class="button-small" :disabled="loading" @click="loadBatches">
          刷新
        </button>
      </div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>批次</th>
              <th>周期</th>
              <th>状态</th>
              <th class="numeric">行数</th>
              <th>创建时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="batch in batches" :key="batch.id" class="clickable-row" @click="openBatch(batch)">
              <td class="code-label">{{ batch.id.slice(0, 12) }}</td>
              <td>{{ batch.period }}</td>
              <td>
                <span
                  class="badge"
                  :class="
                    batch.status === 'applied'
                      ? 'badge-approved'
                      : batch.status === 'rejected'
                        ? 'badge-rejected'
                        : 'badge-pending'
                  "
                  >{{
                    batch.status === "applied"
                      ? "已应用"
                      : batch.status === "rejected"
                        ? "已阻断"
                        : "待应用"
                  }}</span
                >
              </td>
              <td class="numeric">{{ batch.recordCount }}</td>
              <td class="muted">{{ batch.createdAt }}</td>
            </tr>
            <tr v-if="!batches.length">
              <td colspan="5" class="empty">
                {{ loading ? "正在加载批次…" : "还没有数据批次" }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <section v-if="detail" class="card">
      <div class="section-heading">
        <div>
          <h2>批次详情</h2>
          <p class="hint">
            状态：{{ detail.batch.status }} · 行数：{{ detail.batch.recordCount }} ·
            校验和：{{ detail.batch.checksum.slice(0, 12) }}
          </p>
        </div>
        <div class="inline-filters">
          <button
            v-if="detail.batch.status === 'validated'"
            class="button primary"
            :disabled="busy"
            @click="apply"
          >
            应用该批次
          </button>
          <button class="button" :disabled="busy" @click="replay">重放</button>
        </div>
      </div>

      <div v-if="detail.batch.rejectReason" class="error-box">
        {{ detail.batch.rejectReason }}
      </div>

      <div v-if="detail.issues.length" class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>行</th>
              <th>规则</th>
              <th>字段</th>
              <th>说明</th>
              <th>级别</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="issue in detail.issues" :key="`${issue.rowNo}-${issue.rule}`">
              <td class="numeric">{{ issue.rowNo }}</td>
              <td class="code-label">{{ issue.rule }}</td>
              <td>{{ issue.field }}</td>
              <td>{{ issue.message }}</td>
              <td>
                <span
                  class="badge"
                  :class="issue.severity === 'error' ? 'badge-red' : 'badge-yellow'"
                  >{{ issue.severity === "error" ? "错误" : "警告" }}</span
                >
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>指标</th>
              <th class="numeric">分子</th>
              <th class="numeric">分母</th>
              <th class="numeric">值</th>
              <th>单位</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="fact in detail.facts" :key="fact.indicatorCode">
              <td class="code-label">{{ fact.indicatorCode }}</td>
              <td class="numeric">{{ fact.numerator ?? "—" }}</td>
              <td class="numeric">{{ fact.denominator ?? "—" }}</td>
              <td class="numeric">{{ fact.value ?? "—" }}</td>
              <td>{{ fact.unit || "—" }}</td>
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
textarea {
  width: 100%;
  font-family: "Cascadia Mono", "Consolas", monospace;
  font-size: 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 10px 12px;
}
</style>
