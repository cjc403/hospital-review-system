<script setup lang="ts">
import { hasPermission } from "../auth";
import { computed, onMounted, ref, watch } from "vue";
import { errorText, v2 } from "./api";
import type { V2Clause, V2Package, V2VersionDetail } from "./types";
import { notify } from "../store";

const packages = ref<V2Package[]>([]);
const versionId = ref("");
const detail = ref<V2VersionDetail | null>(null);
const chapter = ref("ALL");
const selected = ref<V2Clause | null>(null);
const loading = ref(false);
const busy = ref(false);
const error = ref("");

const canEdit = computed(() => hasPermission("standard.write"));

const clauses = computed(() =>
  (detail.value?.clauses || []).filter(
    (item) => chapter.value === "ALL" || item.chapterCode === chapter.value,
  ),
);
const indicators = computed(() =>
  (detail.value?.indicators || []).filter(
    (item) => chapter.value === "ALL" || item.chapterCode === chapter.value,
  ),
);
const chapterTitle = computed(
  () =>
    detail.value?.chapters.find((item) => item.code === chapter.value)?.title ||
    "全部章节",
);

async function loadPackages() {
  loading.value = true;
  error.value = "";
  try {
    const result = await v2<{ packages: V2Package[] }>("/standards");
    packages.value = result.packages;
    const versions = result.packages.flatMap((item) => item.versions);
    const preferred =
      versions.find((item) => item.status === "published") || versions[0];
    if (preferred && !versionId.value) versionId.value = preferred.id;
  } catch (e) {
    error.value = errorText(e);
  } finally {
    loading.value = false;
  }
}

async function loadVersion(id: string) {
  if (!id) return;
  loading.value = true;
  error.value = "";
  try {
    detail.value = await v2<V2VersionDetail>(`/standards/versions/${id}`);
    selected.value = null;
    chapter.value = "ALL";
  } catch (e) {
    error.value = errorText(e);
  } finally {
    loading.value = false;
  }
}

async function publish() {
  if (!detail.value) return;
  busy.value = true;
  try {
    await v2(`/standards/versions/${detail.value.version.id}/publish`, {
      method: "POST",
      body: "{}",
    });
    notify("标准版本已发布，内容进入只读状态");
    await loadPackages();
    await loadVersion(detail.value.version.id);
  } catch (e) {
    notify(errorText(e), true);
  } finally {
    busy.value = false;
  }
}

async function revise() {
  if (!detail.value) return;
  busy.value = true;
  try {
    const created = await v2<{ id: string }>(
      `/standards/versions/${detail.value.version.id}/revision`,
      { method: "POST", body: JSON.stringify({ note: "工作台发起修订" }) },
    );
    notify("已生成新草稿版本，可继续修订");
    await loadPackages();
    versionId.value = created.id;
    await loadVersion(created.id);
  } catch (e) {
    notify(errorText(e), true);
  } finally {
    busy.value = false;
  }
}

watch(versionId, (id) => loadVersion(id));
onMounted(loadPackages);
</script>

<template>
  <div class="v2-view">
    <div v-if="error" class="error-box">
      {{ error }}
      <button class="text-button" @click="loadPackages">重试</button>
    </div>

    <section class="card">
      <div class="section-heading">
        <div>
          <h2>标准与条款中心</h2>
          <p class="hint">
            标准包按版本管理：发布后条款、指标与前置只读，修订只能生成新版本。
          </p>
        </div>
        <div class="inline-filters">
          <select v-model="versionId" aria-label="选择标准版本">
            <option v-for="pkg in packages" :key="pkg.id" disabled>
              {{ pkg.title }}
            </option>
            <template v-for="pkg in packages" :key="`${pkg.id}-versions`">
              <option
                v-for="ver in pkg.versions"
                :key="ver.id"
                :value="ver.id"
              >
                v{{ ver.versionNo }} · {{ ver.status }} ·
                {{ pkg.code }}
              </option>
            </template>
          </select>
          <button
            v-if="canEdit && detail?.version.status === 'draft'"
            class="button primary"
            :disabled="busy"
            @click="publish"
          >
            发布该版本
          </button>
          <button
            v-if="canEdit"
            class="button"
            :disabled="busy || !detail"
            @click="revise"
          >
            生成修订版本
          </button>
        </div>
      </div>

      <div v-if="loading && !detail" class="initial-loader">
        <span class="spinner"></span>正在读取标准版本…
      </div>

      <template v-else-if="detail">
        <div class="stats-strip">
          <div class="metric-card">
            <span class="metric-label">版本状态</span>
            <strong class="metric-number">{{
              detail.version.status === "published" ? "已发布" : "草稿"
            }}</strong>
            <span class="metric-foot"
              >v{{ detail.version.versionNo }} ·
              {{ detail.version.publishedOn || "未发布" }}</span
            >
          </div>
          <div class="metric-card">
            <span class="metric-label">章节 / 条款</span>
            <strong class="metric-number"
              >{{ detail.counts.chapters }} /
              {{ detail.counts.clauses }}</strong
            >
            <span class="metric-foot">来源：{{ detail.version.appliesTo }}</span>
          </div>
          <div class="metric-card">
            <span class="metric-label">指标 / 前置</span>
            <strong class="metric-number"
              >{{ detail.counts.indicators }} /
              {{ detail.counts.prerequisites }}</strong
            >
            <span class="metric-foot">覆盖集，非完整 203 条</span>
          </div>
          <div class="metric-card">
            <span class="metric-label">校验和</span>
            <strong class="metric-number small-code">{{
              (detail.version.checksum || "").slice(0, 12)
            }}</strong>
            <span class="metric-foot">SHA-256 前 12 位</span>
          </div>
        </div>

        <div class="tabs page-tabs" role="tablist" aria-label="章节筛选">
          <button
            :class="{ active: chapter === 'ALL' }"
            @click="chapter = 'ALL'"
          >
            全部章节
          </button>
          <button
            v-for="item in detail.chapters"
            :key="item.code"
            :class="{ active: chapter === item.code }"
            @click="chapter = item.code"
          >
            {{ item.title }}
          </button>
        </div>

        <div class="table-card">
          <div class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>条款</th>
                  <th>名称</th>
                  <th>责任范围</th>
                  <th>证据类型</th>
                  <th>来源定位</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="item in clauses"
                  :key="item.code"
                  class="clickable-row"
                  @click="selected = item"
                >
                  <td class="code-label">{{ item.code }}</td>
                  <td>{{ item.title }}</td>
                  <td>{{ item.ownerScope }}</td>
                  <td>{{ item.evidenceType }}</td>
                  <td class="muted">{{ item.sourceLocator }}</td>
                </tr>
                <tr v-if="!clauses.length">
                  <td colspan="5" class="empty">该章节下暂无条款</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <div v-if="selected" class="card info-strip">
          <h3>{{ selected.code }} · {{ selected.title }}</h3>
          <p>{{ selected.requirement }}</p>
          <p class="hint">
            评价方式：{{ selected.evaluationMethod }} · 责任范围：{{
              selected.ownerScope
            }}
          </p>
        </div>

        <div class="table-card">
          <div class="section-heading">
            <h3>{{ chapterTitle }} · 指标定义</h3>
            <span class="muted">{{ indicators.length }} 项</span>
          </div>
          <div class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>编码</th>
                  <th>指标</th>
                  <th>归属条款</th>
                  <th>单位</th>
                  <th>方向</th>
                  <th>计分类型</th>
                  <th class="numeric">目标 / 告警</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="item in indicators" :key="item.code">
                  <td class="code-label">{{ item.code }}</td>
                  <td>{{ item.name }}</td>
                  <td>{{ item.clauseCode }}</td>
                  <td>{{ item.unit || "—" }}</td>
                  <td>{{ item.direction === "high" ? "高优" : "低优" }}</td>
                  <td>
                    {{
                      item.scoreType === "all_or_nothing"
                        ? "全有全无"
                        : "区间趋势"
                    }}
                  </td>
                  <td class="numeric">
                    {{ item.target ?? "—" }} / {{ item.warn ?? "—" }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </template>
    </section>
  </div>
</template>

<style scoped>
.v2-view {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.small-code {
  font-size: 15px;
  letter-spacing: 0.5px;
}
</style>
