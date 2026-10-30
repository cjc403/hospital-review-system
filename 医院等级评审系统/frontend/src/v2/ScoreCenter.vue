<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { errorText, v2, v2Download } from "./api";
import { conclusionLabel, gradeLabel } from "./domain";
import type {
  V2Package,
  V2ScoreItem,
  V2ScoreRunDetail,
  V2ScoreRunSummary,
} from "./types";
import { notify, role } from "../store";

const runs = ref<V2ScoreRunSummary[]>([]);
const packages = ref<V2Package[]>([]);
const detail = ref<V2ScoreRunDetail | null>(null);
const loading = ref(false);
const busy = ref(false);
const error = ref("");
const compare = ref({ left: "", right: "" });
const diff = ref<{
  added: string[];
  recovered: string[];
  worsened: string[];
  changedRule: string[];
} | null>(null);

const form = ref({ versionId: "", cycleStart: 2023, cycleEnd: 2026 });
const canRun = computed(() => role.value === "quality");
const years = [2023, 2024, 2025, 2026];

async function loadReference() {
  try {
    const result = await v2<{ packages: V2Package[] }>("/standards");
    packages.value = result.packages;
    const published = result.packages
      .flatMap((pkg) => pkg.versions)
      .find((ver) => ver.status === "published");
    if (published) form.value.versionId = published.id;
  } catch (e) {
    error.value = errorText(e);
  }
}

async function loadRuns() {
  loading.value = true;
  try {
    const result = await v2<{ items: V2ScoreRunSummary[] }>("/score-runs?limit=20");
    runs.value = result.items;
    if (!compare.value.left && result.items[0]) compare.value.left = result.items[0].id;
    if (!compare.value.right && result.items[1]) compare.value.right = result.items[1].id;
  } catch (e) {
    error.value = errorText(e);
  } finally {
    loading.value = false;
  }
}

async function createRun() {
  busy.value = true;
  try {
    detail.value = await v2<V2ScoreRunDetail>("/score-runs", {
      method: "POST",
      body: JSON.stringify(form.value),
    });
    notify("已生成试算快照");
    await loadRuns();
  } catch (e) {
    notify(errorText(e), true);
  } finally {
    busy.value = false;
  }
}

async function openRun(run: V2ScoreRunSummary) {
  try {
    detail.value = await v2<V2ScoreRunDetail>(`/score-runs/${run.id}`);
  } catch (e) {
    notify(errorText(e), true);
  }
}

async function downloadReport() {
  if (!detail.value) return;
  try {
    await v2Download(
      `/score-runs/${detail.value.run.id}/report`,
      `review-report-${detail.value.run.id}.docx`,
    );
    notify("报告已开始下载");
  } catch (e) {
    notify(errorText(e), true);
  }
}

async function runCompare() {
  if (!compare.value.left || !compare.value.right) {
    notify("请选择两次试算", true);
    return;
  }
  try {
    diff.value = await v2(
      `/score-runs/${compare.value.left}/compare/${compare.value.right}`,
    );
  } catch (e) {
    notify(errorText(e), true);
  }
}

function conclusion(item: V2ScoreItem): string {
  return conclusionLabel(item);
}

onMounted(async () => {
  await Promise.all([loadReference(), loadRuns()]);
});
</script>

<template>
  <div class="v2-view">
    <div v-if="error" class="error-box">
      {{ error }}
      <button class="text-button" @click="loadRuns">重试</button>
    </div>

    <section v-if="canRun" class="card">
      <div class="section-heading">
        <div>
          <h2>创建周期试算</h2>
          <p class="hint">
            只使用已应用批次的事实；结果保存为不可变快照，报告引用该快照。
          </p>
        </div>
      </div>
      <div class="form-grid">
        <label class="field">
          <span>标准版本</span>
          <select v-model="form.versionId">
            <template v-for="pkg in packages" :key="pkg.id">
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
          <span>起始年</span>
          <select v-model.number="form.cycleStart">
            <option v-for="year in years" :key="year" :value="year">{{ year }}</option>
          </select>
        </label>
        <label class="field">
          <span>结束年</span>
          <select v-model.number="form.cycleEnd">
            <option v-for="year in years" :key="year" :value="year">{{ year }}</option>
          </select>
        </label>
      </div>
      <div class="form-actions">
        <button class="button primary" :disabled="busy" @click="createRun">
          生成试算快照
        </button>
        <span class="hint">周期须 1–4 年，且只能选择已发布标准版本</span>
      </div>
    </section>

    <section class="table-card">
      <div class="section-heading">
        <h3>试算记录</h3>
        <button class="button-small" :disabled="loading" @click="loadRuns">
          刷新
        </button>
      </div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>试算</th>
              <th>周期</th>
              <th>等次</th>
              <th class="numeric">符合 / 纳入</th>
              <th>创建时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="run in runs" :key="run.id" class="clickable-row" @click="openRun(run)">
              <td class="code-label">{{ run.id.slice(0, 12) }}</td>
              <td>{{ run.cycleStart }}–{{ run.cycleEnd }}</td>
              <td>
                <span class="badge badge-green">{{
                  gradeLabel(run.grade)
                }}</span>
              </td>
              <td class="numeric">{{ run.conformCount }} / {{ run.includedCount }}</td>
              <td class="muted">{{ run.createdAt }}</td>
            </tr>
            <tr v-if="!runs.length">
              <td colspan="5" class="empty">
                {{ loading ? "正在加载试算…" : "还没有试算记录" }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <section v-if="detail" class="card">
      <div class="section-heading">
        <div>
          <h2>试算结果</h2>
          <p class="hint">
            {{ detail.run.packageTitle }} · v{{ detail.run.versionNo }} ·
            {{ detail.run.cycleStart }}–{{ detail.run.cycleEnd }} · 符合
            {{ detail.run.conformCount }}/{{ detail.run.includedCount }}
          </p>
        </div>
        <div class="inline-filters">
          <button class="button" @click="downloadReport">下载快照报告</button>
        </div>
      </div>
      <div v-if="detail.run.deferredReason" class="error-box">
        {{ detail.run.deferredReason }}
      </div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>指标</th>
              <th>年度值</th>
              <th class="numeric">合并值</th>
              <th>结论</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in detail.items" :key="item.indicatorCode">
              <td>{{ item.name }}</td>
              <td class="muted">{{ item.yearValues }}</td>
              <td class="numeric">
                {{ item.mergedValue ?? "—" }}{{ item.unit }}
              </td>
              <td>
                <span
                  class="badge"
                  :class="
                    item.conform === true
                      ? 'badge-green'
                      : item.conform === false
                        ? 'badge-red'
                        : 'badge-na'
                  "
                  >{{ conclusion(item) }}</span
                >
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <section class="card">
      <div class="section-heading">
        <h3>两次试算对比</h3>
        <div class="inline-filters">
          <select v-model="compare.left" aria-label="左侧试算">
            <option v-for="run in runs" :key="run.id" :value="run.id">
              {{ run.id.slice(0, 10) }} · {{ run.cycleStart }}–{{ run.cycleEnd }}
            </option>
          </select>
          <span class="muted">对比</span>
          <select v-model="compare.right" aria-label="右侧试算">
            <option v-for="run in runs" :key="run.id" :value="run.id">
              {{ run.id.slice(0, 10) }} · {{ run.cycleStart }}–{{ run.cycleEnd }}
            </option>
          </select>
          <button class="button-small" @click="runCompare">比较</button>
        </div>
      </div>
      <div v-if="diff" class="stats-strip">
        <div class="metric-card">
          <span class="metric-label">新增指标</span>
          <strong class="metric-number">{{ diff.added.length }}</strong>
          <span class="metric-foot">{{ diff.added.join("、") || "无" }}</span>
        </div>
        <div class="metric-card">
          <span class="metric-label">恢复符合</span>
          <strong class="metric-number positive">{{ diff.recovered.length }}</strong>
          <span class="metric-foot">{{ diff.recovered.join("、") || "无" }}</span>
        </div>
        <div class="metric-card">
          <span class="metric-label">转为不符合</span>
          <strong class="metric-number negative">{{ diff.worsened.length }}</strong>
          <span class="metric-foot">{{ diff.worsened.join("、") || "无" }}</span>
        </div>
        <div class="metric-card">
          <span class="metric-label">原因码变化</span>
          <strong class="metric-number">{{ diff.changedRule.length }}</strong>
          <span class="metric-foot">{{ diff.changedRule.join("、") || "无" }}</span>
        </div>
      </div>
      <p v-else class="hint">选择两次试算后点击比较，查看恢复、恶化与原因码变化。</p>
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
