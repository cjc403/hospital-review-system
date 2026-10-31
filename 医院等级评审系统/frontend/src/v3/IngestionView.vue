<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { errorText, v3 } from "./api";
import type { V3Batch, V3BatchDetail, V3Mapping } from "./types";
import { notify, role } from "../store";

const sources = ref<{ code: string; name: string; kind: string }[]>([]);
const batches = ref<V3Batch[]>([]);
const detail = ref<V3BatchDetail | null>(null);
const mapping = ref<V3Mapping | null>(null);
const loading = ref(false);
const busy = ref(false);
const error = ref("");

const sourceCode = ref("CSV-IMPORT");
const form = ref({
  period: "2026-10",
  csv: "i01,CARD,11,2000,,%\ni03,SURG,6,2000,,%\ni11,CARD,400,100,,\ni11,SURG,600,300,,\ni12,ALL,,,870,张",
});
const fieldDraft = ref<Record<string, string>>({
  metricCode: "code",
  deptCode: "dept",
  numerator: "num",
  denominator: "den",
  value: "val",
  unit: "unit",
});
const codeMapDraft = ref("{}");

const canWrite = computed(() => ["quality", "information"].includes(role.value));

async function loadSources() {
  try {
    const result = await v3<{ items: { code: string; name: string; kind: string }[] }>("/data-sources");
    sources.value = result.items;
  } catch (e) {
    error.value = errorText(e);
  }
}

async function loadMapping() {
  try {
    const result = await v3<{ items: V3Mapping[] }>(`/source-mappings?sourceCode=${sourceCode.value}`);
    mapping.value = result.items[0] || null;
    if (mapping.value) {
      fieldDraft.value = { ...fieldDraft.value, ...mapping.value.fields };
      codeMapDraft.value = JSON.stringify(mapping.value.codeMap || {}, null, 2);
    }
  } catch (e) {
    error.value = errorText(e);
  }
}

async function saveMapping() {
  busy.value = true;
  try {
    let codeMap: unknown = {};
    try {
      codeMap = JSON.parse(codeMapDraft.value || "{}");
    } catch {
      notify("编码映射必须是合法 JSON", true);
      return;
    }
    await v3("/source-mappings", {
      method: "PUT",
      body: JSON.stringify({ sourceCode: sourceCode.value, fields: fieldDraft.value, codeMap }),
    });
    notify("映射已保存，导入时生效");
    await loadMapping();
  } catch (e) {
    notify(errorText(e), true);
  } finally {
    busy.value = false;
  }
}

function parseRows(text: string) {
  const rows: Record<string, unknown>[] = [];
  for (const line of text.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith("#")) continue;
    const [code, dept, num, den, val, unit] = trimmed.split(",").map((cell) => cell.trim());
    if (!code) continue;
    const row: Record<string, unknown> = { code };
    if (dept) row.dept = dept;
    if (num) row.num = Number(num);
    if (den) row.den = Number(den);
    if (val) row.val = Number(val);
    if (unit !== undefined) row.unit = unit;
    rows.push(row);
  }
  return rows;
}

async function importBatch() {
  const rows = parseRows(form.value.csv);
  if (!rows.length) {
    notify("请按 code,dept,num,den,val,unit 每行一条填写", true);
    return;
  }
  busy.value = true;
  try {
    detail.value = await v3<V3BatchDetail>("/ingestion/batches", {
      method: "POST",
      body: JSON.stringify({ sourceCode: sourceCode.value, period: form.value.period, rows }),
    });
    notify(
      detail.value.batch.status === "validated" ? "批次通过质量校验，可应用" : "批次存在质量错误，已阻断",
      detail.value.batch.status !== "validated",
    );
    await loadBatches();
  } catch (e) {
    notify(errorText(e), true);
  } finally {
    busy.value = false;
  }
}

async function loadBatches() {
  loading.value = true;
  try {
    const result = await v3<{ items: V3Batch[] }>("/ingestion/batches?limit=20");
    batches.value = result.items;
  } catch (e) {
    error.value = errorText(e);
  } finally {
    loading.value = false;
  }
}

async function openBatch(batch: V3Batch) {
  try {
    detail.value = await v3<V3BatchDetail>(`/ingestion/batches/${batch.id}`);
  } catch (e) {
    notify(errorText(e), true);
  }
}

async function apply() {
  if (!detail.value) return;
  busy.value = true;
  try {
    detail.value = await v3<V3BatchDetail>(`/ingestion/batches/${detail.value.batch.id}/apply`, {
      method: "POST",
      body: "{}",
    });
    notify("批次已应用，结果与预警已生成");
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
    detail.value = await v3<V3BatchDetail>(`/ingestion/batches/${detail.value.batch.id}/replay`, {
      method: "POST",
      body: "{}",
    });
    notify("已重放为新批次，重新走映射与质量校验");
    await loadBatches();
  } catch (e) {
    notify(errorText(e), true);
  } finally {
    busy.value = false;
  }
}

onMounted(async () => {
  await loadSources();
  await loadMapping();
  await loadBatches();
});
</script>

<template>
  <div class="v3-view">
    <div v-if="error" class="error-box">
      {{ error }}
      <button class="text-button" @click="loadBatches">重试</button>
    </div>

    <section class="card">
      <div class="section-heading">
        <div>
          <h2>字段映射与编码清洗</h2>
          <p class="hint">映射决定原始列如何变成标准事实；未配置映射时导入会被拒绝。</p>
        </div>
        <div class="inline-filters">
          <select v-model="sourceCode" aria-label="数据源" @change="loadMapping">
            <option v-for="source in sources" :key="source.code" :value="source.code">
              {{ source.name }}（{{ source.kind }}）
            </option>
          </select>
          <span class="subtle-tag">映射版本 {{ mapping?.version ?? "未配置" }}</span>
        </div>
      </div>
      <div class="form-grid">
        <label v-for="(label, key) in { metricCode: '指标编码列', deptCode: '科室列', numerator: '分子列', denominator: '分母列', value: '数值列', unit: '单位列' }" :key="key" class="field">
          <span>{{ label }}</span>
          <input v-model="fieldDraft[key]" :placeholder="key" />
        </label>
      </div>
      <label class="field">
        <span>编码映射（JSON：{"metric":{"别名":"i01"},"dept":{"心内科":"CARD"}}）</span>
        <textarea v-model="codeMapDraft" rows="3" spellcheck="false"></textarea>
      </label>
      <div class="form-actions">
        <button v-if="canWrite" class="button primary" :disabled="busy" @click="saveMapping">保存映射</button>
        <span class="hint">保存后对后续导入生效，旧批次保留原映射版本</span>
      </div>
    </section>

    <section v-if="canWrite" class="card">
      <div class="section-heading">
        <div>
          <h2>导入采集批次</h2>
          <p class="hint">每行 code,dept,num,den,val,unit；错误批次会被阻断，不会产生结果。</p>
        </div>
      </div>
      <div class="form-grid">
        <label class="field">
          <span>周期</span>
          <input v-model="form.period" placeholder="YYYY-MM" />
        </label>
      </div>
      <label class="field">
        <span>原始数据行</span>
        <textarea v-model="form.csv" rows="7" spellcheck="false"></textarea>
      </label>
      <div class="form-actions">
        <button class="button primary" :disabled="busy" @click="importBatch">导入并校验</button>
      </div>
    </section>

    <section class="table-card">
      <div class="section-heading">
        <h3>批次列表</h3>
        <button class="button-small" :disabled="loading" @click="loadBatches">刷新</button>
      </div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>批次</th>
              <th>周期</th>
              <th>状态</th>
              <th class="numeric">行数</th>
              <th>重放来源</th>
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
                  >{{ batch.status === "applied" ? "已应用" : batch.status === "rejected" ? "已阻断" : "待应用" }}</span
                >
              </td>
              <td class="numeric">{{ batch.recordCount }}</td>
              <td class="muted">{{ batch.replayOf ? batch.replayOf.slice(0, 12) : "—" }}</td>
              <td class="muted">{{ batch.createdAt }}</td>
            </tr>
            <tr v-if="!batches.length">
              <td colspan="6" class="empty">{{ loading ? "正在加载批次…" : "还没有批次" }}</td>
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
            状态 {{ detail.batch.status }} · {{ detail.batch.period }} · {{ detail.batch.recordCount }} 行 ·
            {{ detail.batch.rejectReason || "无阻断原因" }}
          </p>
        </div>
        <div class="inline-filters">
          <button
            v-if="detail.batch.status === 'validated'"
            class="button primary"
            :disabled="busy"
            @click="apply"
          >
            应用并计算结果
          </button>
          <button class="button" :disabled="busy" @click="replay">重放</button>
        </div>
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
                <span class="badge" :class="issue.severity === 'error' ? 'badge-red' : 'badge-yellow'">
                  {{ issue.severity === "error" ? "错误" : "警告" }}
                </span>
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
              <th>科室</th>
              <th class="numeric">分子</th>
              <th class="numeric">分母</th>
              <th class="numeric">值</th>
              <th>单位</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="fact in detail.facts" :key="`${fact.metricCode}-${fact.deptCode}`">
              <td class="code-label">{{ fact.metricCode }}</td>
              <td>{{ fact.deptCode }}</td>
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
.v3-view {
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
