<script setup lang="ts">
import { hasPermission } from "../auth";
import { computed, ref, watch } from "vue";
import { ClipboardCheck, Plus, Info } from "lucide-vue-next";
import { workspace as state, busy, canWrite, mutate, role } from "../store";
import { auditGate, formatDate, formatValue } from "../domain";
import type { Reconcile } from "../types";
import Badge from "./Badge.vue";
import Modal from "./Modal.vue";
const resultLabels: Record<string, string> = {
  error: "错误",
  correct: "一致",
  passed: "通过",
  missing: "缺失",
  ok: "一致",
  matched: "一致",
};
const canViewCases = computed(() => hasPermission("member.read"));
const tab = ref(canViewCases.value ? "reconcile" : "samples");
watch(canViewCases, allowed => { if (!allowed) { tab.value = "samples"; selected.value = null; } });
const filter = ref("ALL");
const rows = computed(() =>
  state.value!.reconciliations.filter(
    (x) => filter.value === "ALL" || x.status === filter.value,
  ),
);
const selected = ref<Reconcile | null>(null);
const note = ref("");
const status = ref("explained");
const selectedSampleId = ref("");
const selectedSample = computed(
  () =>
    state.value!.auditSamples.find((x) => x.id === selectedSampleId.value) ||
    state.value!.auditSamples.at(-1),
);
const creating = ref(false);
function open(item: Reconcile) {
  selected.value = item;
  note.value = item.note;
  status.value = "explained";
}
async function save() {
  if (
    selected.value &&
    (await mutate(
      `/reconciliations/${selected.value.id}`,
      { status: status.value, note: note.value },
      "PATCH",
      "病例差异说明已保存",
    ))
  )
    selected.value = null;
}
async function create() {
  if (
    await mutate(
      "/audit-samples",
      { period: state.value!.meta.period },
      "POST",
      "已生成新的条款抽查快照",
    )
  ) {
    creating.value = false;
    selectedSampleId.value = state.value!.auditSamples.at(-1)?.id || "";
  }
}
</script>
<template>
  <div class="page-intro">
    <div>
      <h2>数据核查</h2>
      <p>核验病案首页与 HQMS 的数据差异，留存每次条款抽查结果。</p>
    </div>
    <button
      v-if="canWrite && tab === 'samples'"
      class="button primary"
      :disabled="busy"
      @click="creating = true"
    >
      <Plus :size="16" />发起条款抽查
    </button>
  </div>
  <div class="tabs page-tabs">
    <button v-if="canViewCases" :class="{ active: tab === 'reconcile' }" @click="tab = 'reconcile'">
      病例差异
      <span class="count">{{ state!.reconciliations.length }}</span></button
    ><button :class="{ active: tab === 'samples' }" @click="tab = 'samples'">
      条款抽查 <span class="count">{{ state!.auditSamples.length }}</span>
    </button>
  </div>
  <template v-if="canViewCases && tab === 'reconcile'"
    ><div class="info-strip">
      <Info
        :size="17"
      />病例差异用于追溯原始数据问题，不计入条款错误率，也不会直接触发延期评审。
    </div>
    <section class="card table-card">
      <div class="section-heading">
        <h3>病例差异清单</h3>
        <select v-model="filter" aria-label="差异状态">
          <option value="ALL">全部状态</option>
          <option value="pending">待说明</option>
          <option value="explained">已说明</option>
        </select>
      </div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>脱敏住院号</th>
              <th>出院日期 / 科室</th>
              <th>差异项目</th>
              <th>首页值</th>
              <th>HQMS 值</th>
              <th>状态</th>
              <th>说明</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in rows" :key="item.id">
              <td class="numeric">{{ item.inpatientNo }}</td>
              <td>
                {{ item.dischargeDate
                }}<small class="cell-sub">{{ item.deptName }}</small>
              </td>
              <td>{{ item.diffType }}</td>
              <td>{{ item.homeValue }}</td>
              <td>{{ item.hqmsValue }}</td>
              <td><Badge :value="item.status" /></td>
              <td class="truncate-cell" :title="item.note">
                {{ item.note || "待补充" }}
              </td>
              <td>
                <button
                  v-if="canWrite"
                  class="text-button"
                  :disabled="busy"
                  @click="open(item)"
                >
                  {{ item.status === "explained" ? "修改说明" : "补充说明" }}
                </button>
              </td>
            </tr>
          </tbody>
        </table>
        <div v-if="!rows.length" class="empty">暂无符合条件的病例差异</div>
      </div>
    </section></template
  >
  <template v-else
    ><div class="info-strip">
      <Info :size="17" />条款相对偏差 ≥10% 判为错误；错误条款率严格大于 10%
      才触发延期。每次抽查生成新记录。
    </div>
    <div v-if="!selectedSample" class="card empty">
      <ClipboardCheck :size="32" /><strong>尚无条款抽查记录</strong>
      <p>质控管理员可发起抽查，生成当前周期的核查快照。</p>
    </div>
    <template v-else
      ><div class="card sample-summary">
        <div>
          <span class="eyebrow">条款核查快照</span>
          <h3>{{ selectedSample.period }} 数据真实性抽查</h3>
          <p class="hint">
            {{ formatDate(selectedSample.createdAt) }} · {{ selectedSample.id }}
          </p>
        </div>
        <div class="sample-metric">
          <small>错误 / 抽查条款</small
          ><strong
            >{{ selectedSample.errorCount
            }}<span> / {{ selectedSample.sampleCount }}</span></strong
          >
        </div>
        <div class="sample-metric">
          <small>错误条款率</small
          ><strong
            :class="
              auditGate(selectedSample.errorRate) ? 'negative' : 'positive'
            "
            >{{ (selectedSample.errorRate * 100).toFixed(1) }}%</strong
          >
        </div>
        <Badge
          :value="auditGate(selectedSample.errorRate) ? 'red' : 'green'"
          :text="
            auditGate(selectedSample.errorRate)
              ? '触发延期条件'
              : '未触发延期条件'
          "
        />
      </div>
      <section class="card table-card">
        <div class="section-heading">
          <h3>抽查条款结果</h3>
          <select v-model="selectedSampleId" aria-label="历史抽查">
            <option value="">最新抽查</option>
            <option
              v-for="sample in [...state!.auditSamples].reverse()"
              :key="sample.id"
              :value="sample.id"
            >
              {{ formatDate(sample.createdAt) }} · {{ sample.id }}
            </option>
          </select>
        </div>
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>指标条款</th>
                <th>首页值</th>
                <th>HQMS 值</th>
                <th>相对偏差</th>
                <th>核查结果</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in selectedSample.items" :key="item.indicatorId">
                <td>
                  <strong>{{ item.name }}</strong>
                </td>
                <td class="numeric">{{ formatValue(item.homeValue) }}</td>
                <td class="numeric">{{ formatValue(item.hqmsValue) }}</td>
                <td>
                  {{
                    item.deviation == null
                      ? "—"
                      : `${(item.deviation * 100).toFixed(1)}%`
                  }}
                </td>
                <td>{{ resultLabels[item.result] || item.result }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section></template
    ></template
  >
  <Modal v-if="selected" title="病例差异说明" @close="selected = null"
    ><form @submit.prevent="save">
      <div class="info-strip">
        {{ selected.inpatientNo }} · {{ selected.diffType }}
      </div>
      <label class="field"
        >处理状态<select v-model="status">
          <option value="explained">已说明</option>
          <option value="pending">待说明</option>
        </select></label
      ><label class="field"
        >差异原因与处理说明<textarea
          v-model="note"
          required
          rows="5"
          placeholder="说明数据差异的原因、核验依据及处理结果"
        ></textarea>
      </label>
      <div class="form-actions">
        <button type="button" class="button" @click="selected = null">
          取消</button
        ><button class="button primary" :disabled="busy || !note.trim()">
          {{ busy ? "保存中…" : "保存说明" }}
        </button>
      </div>
    </form></Modal
  >
  <Modal v-if="creating" title="发起条款抽查" @close="creating = false"
    ><div class="confirm-content">
      <ClipboardCheck :size="36" />
      <h3>生成 {{ state!.meta.period }} 抽查快照</h3>
      <p>
        依据预置可核查样例生成一份新记录，保留历次结果。抽查将自动更新系统数据真实性条款。
      </p>
    </div>
    <div class="form-actions">
      <button class="button" @click="creating = false">取消</button
      ><button class="button primary" :disabled="busy" @click="create">
        {{ busy ? "抽查中…" : "确认发起抽查" }}
      </button>
    </div></Modal
  >
</template>
