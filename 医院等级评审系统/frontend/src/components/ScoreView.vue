<script setup lang="ts">
import { computed, ref } from "vue";
import {
  Calculator,
  Download,
  CheckCheck,
  Info,
  AlertTriangle,
} from "lucide-vue-next";
import { workspace as state, busy, canWrite, mutate, download } from "../store";
import { formatDate, formatValue, label, scoreReasonLabel } from "../domain";
import Modal from "./Modal.vue";
import Badge from "./Badge.vue";
const cycleStart = ref(2023),
  cycleEnd = ref(2026),
  selectedId = ref("");
const selected = computed(
  () =>
    state.value!.scoreRuns.find((x) => x.id === selectedId.value) ||
    state.value!.scoreRuns.at(-1),
);
const adopting = ref(false);
async function run() {
  if (
    await mutate(
      "/score-runs",
      { cycleStart: cycleStart.value, cycleEnd: cycleEnd.value },
      "POST",
      "样例试算已完成并保存快照",
    )
  )
    selectedId.value = state.value!.scoreRuns.at(-1)?.id || "";
}
async function adopt() {
  if (
    selected.value &&
    (await mutate(
      `/score-runs/${selected.value.id}/adopt`,
      {},
      "POST",
      "已采用本次演示试算快照",
    ))
  )
    adopting.value = false;
}
</script>
<template>
  <div class="page-intro">
    <div>
      <h2>模拟等次</h2>
      <p>选择年度范围，保存一次可追溯的样例试算和报告快照。</p>
    </div>
    <span class="badge badge-yellow"
      ><AlertTriangle :size="13" />仅供演示试算</span
    >
  </div>
  <div class="warning-strip">
    <AlertTriangle :size="19" />
    <div>
      <strong>样例覆盖不足，不构成正式评审结论</strong>
      <p>
        当前仅 {{ state!.indicators.length }} 项样例，未覆盖
        {{ state!.meta.totalMonitoringClauses }} 条完整监测条款及 60%
        覆盖要求。所有等次、采用结果和导出报告均明确标记“演示试算”。
      </p>
    </div>
  </div>
  <section class="card score-controls">
    <div>
      <h3>年度计算范围</h3>
      <p class="hint">支持连续 1–4 年，年度值按固定样例数据汇总</p>
    </div>
    <div class="inline-filters">
      <label
        >起始年<select v-model.number="cycleStart" aria-label="试算起始年">
          <option
            v-for="year in [2023, 2024, 2025, 2026]"
            :key="year"
            :value="year"
          >
            {{ year }} 年
          </option>
        </select></label
      ><span class="muted">至</span
      ><label
        >结束年<select v-model.number="cycleEnd" aria-label="试算结束年">
          <option
            v-for="year in [2023, 2024, 2025, 2026]"
            :key="year"
            :value="year"
          >
            {{ year }} 年
          </option>
        </select></label
      ><button
        v-if="canWrite"
        class="button primary"
        :disabled="busy || cycleEnd < cycleStart || cycleEnd - cycleStart > 3"
        @click="run"
      >
        <Calculator :size="16" />{{ busy ? "试算中…" : "执行样例试算" }}
      </button>
    </div>
  </section>
  <p v-if="cycleEnd < cycleStart" class="warning-text">结束年不得早于起始年</p>
  <div v-if="!selected" class="card empty">
    <Calculator :size="38" /><strong>尚无试算结果</strong>
    <p>由质控管理员执行试算后，可查看计算依据并下载 DOCX 报告。</p>
  </div>
  <template v-else
    ><section class="card score-result">
      <div>
        <span class="eyebrow">DEMO ASSESSMENT · 保存快照</span>
        <h2>{{ label(selected.grade) }}</h2>
        <p>
          {{ selected.cycleStart }}—{{ selected.cycleEnd }} 年 ·
          {{ formatDate(selected.createdAt) }}
        </p>
        <span class="subtle-tag">{{
          selected.adopted ? "当前已采用 · 演示用途" : "待采用 · 演示用途"
        }}</span>
      </div>
      <div class="score-ratio">
        <strong>{{ (selected.ratio * 100).toFixed(1) }}<small>%</small></strong
        ><span
          >样例符合率 · {{ selected.conformCount }} /
          {{ selected.includedCount }} 项</span
        >
      </div>
      <div class="score-buttons">
        <button
          class="button"
          :disabled="busy"
          @click="
            download(
              `/reports/${selected.id}`,
              `医院评审_演示试算_${selected.id}.docx`,
            )
          "
        >
          <Download :size="16" />下载 DOCX 报告</button
        ><button
          v-if="canWrite"
          class="button primary"
          :disabled="busy || selected.adopted"
          @click="adopting = true"
        >
          <CheckCheck :size="16" />{{
            selected.adopted ? "已采用该试算" : "采用该试算快照"
          }}
        </button>
      </div>
    </section>
    <div v-if="selected.warnings.length" class="card warning-list">
      <h3><Info :size="17" />试算提示</h3>
      <p v-for="(warning, index) in selected.warnings" :key="index">
        {{ warning }}
      </p>
    </div>
    <section class="card table-card">
      <div class="section-heading">
        <h3>年度计算明细</h3>
        <select v-model="selectedId" aria-label="历史试算">
          <option value="">最新试算</option>
          <option
            v-for="run in [...state!.scoreRuns].reverse()"
            :key="run.id"
            :value="run.id"
          >
            {{ formatDate(run.createdAt) }} · {{ run.cycleStart }}—{{
              run.cycleEnd
            }}
            · {{ run.id }}
          </option>
        </select>
      </div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>指标名称</th>
              <th
                v-for="year in selected.items[0]?.yearValues || []"
                :key="year.year"
              >
                {{ year.year }} 年
              </th>
              <th>合并计算值</th>
              <th>样例判定</th>
              <th>计算依据</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in selected.items" :key="item.indicatorId">
              <td>
                <strong>{{ item.name }}</strong>
              </td>
              <td
                v-for="year in item.yearValues"
                :key="year.year"
                class="numeric"
              >
                {{ formatValue(year.value, item.unit) }}
              </td>
              <td class="numeric">
                {{ formatValue(item.mergedValue, item.unit) }}
              </td>
              <td>
                <Badge
                  :value="
                    item.conform == null ? 'na' : item.conform ? 'green' : 'red'
                  "
                  :text="
                    item.conform == null
                      ? '未纳入'
                      : item.conform
                        ? '符合'
                        : '不符合'
                  "
                />
              </td>
              <td>{{ scoreReasonLabel(item.reasonCode) }}</td>
            </tr>
          </tbody>
        </table>
        <div v-if="!selected.items.length" class="empty">
          本次没有可计算指标
        </div>
      </div>
    </section></template
  >
  <Modal
    v-if="adopting && selected"
    title="采用演示试算快照"
    @close="adopting = false"
    ><div class="confirm-content">
      <CheckCheck :size="36" />
      <h3>采用 {{ selected.cycleStart }}—{{ selected.cycleEnd }} 样例试算</h3>
      <p>
        采用后保留计算、前置结论与抽查结果快照，导出报告绑定本次数据。该操作只用于演示，不代表正式等次。
      </p>
    </div>
    <div class="form-actions">
      <button class="button" @click="adopting = false">取消</button
      ><button class="button primary" :disabled="busy" @click="adopt">
        确认采用样例试算
      </button>
    </div></Modal
  >
</template>
