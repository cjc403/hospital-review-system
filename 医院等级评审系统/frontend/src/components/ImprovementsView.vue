<script setup lang="ts">
import { computed, ref } from "vue";
import {
  Plus,
  ArrowRight,
  Info,
  Paperclip,
  RotateCcw,
  Activity,
} from "lucide-vue-next";
import { workspace as state, busy, canWrite, mutate, reopenImprovement } from "../store";
import { formatValue, improvementNext, label } from "../domain";
import type { Improvement } from "../types";
import Badge from "./Badge.vue";
import Modal from "./Modal.vue";
import EvidencePanel from "./EvidencePanel.vue";
const creating = ref(false),
  filter = ref("ALL"),
  selectedId = ref("");
const createForm = ref({
  indicatorId: "",
  origin: "red_light",
  owner: "",
  dueDate: "2026-11-30",
});
const form = ref({
  status: "",
  owner: "",
  dueDate: "",
  actionText: "",
  closeMode: "improved",
  closeNote: "",
});
const selected = computed(() =>
  state.value!.improvements.find((x) => x.id === selectedId.value),
);
const rows = computed(() =>
  [...state.value!.improvements]
    .reverse()
    .filter((x) => filter.value === "ALL" || x.status === filter.value),
);
const stages = [
  "opened",
  "assigned",
  "doing",
  "evidence_ready",
  "verified",
  "closed",
];
function create() {
  createForm.value = {
    indicatorId:
      state.value!.indicators.find((x) => x.light === "red")?.id ||
      state.value!.indicators[0]?.id ||
      "",
    origin: "red_light",
    owner: "",
    dueDate: "2026-11-30",
  };
  creating.value = true;
}
async function saveCreate() {
  if (
    await mutate("/improvements", createForm.value, "POST", "持续改进单已创建")
  )
    creating.value = false;
}
function open(item: Improvement) {
  selectedId.value = item.id;
  form.value = {
    status: item.status,
    owner: item.owner,
    dueDate: item.dueDate,
    actionText: item.actionText,
    closeMode: item.closeMode || "improved",
    closeNote: item.closeNote,
  };
}
const next = computed(() =>
  selected.value ? improvementNext(selected.value.status) : null,
);
const unit = computed(
  () =>
    state.value!.indicators.find((i) => i.id === selected.value?.indicatorId)
      ?.unit || "",
);
const readyError = computed(
  () =>
    form.value.status === "evidence_ready" &&
    (!form.value.actionText.trim() || !selected.value?.evidenceCount),
);
async function save() {
  if (
    selected.value &&
    (await mutate(
      `/improvements/${selected.value.id}`,
      form.value,
      "PATCH",
      "改进进度已保存",
    ))
  )
    selectedId.value = "";
}
async function reopen() {
  if (
    selected.value &&
    (await reopenImprovement(selected.value.id))
  )
    selectedId.value = "";
}
</script>
<template>
  <div class="page-intro">
    <div>
      <h2>持续改进</h2>
      <p>把指标预警转化为明确责任、有效措施和可验证的改善。</p>
    </div>
    <button
      v-if="canWrite"
      class="button primary"
      :disabled="busy"
      @click="create"
    >
      <Plus :size="16" />发起持续改进
    </button>
  </div>
  <div class="workflow-track">
    <div v-for="(stage, index) in stages" :key="stage">
      <span class="stage-number">{{ index + 1 }}</span
      ><strong>{{ label(stage) }}</strong
      ><small
        >{{
          state!.improvements.filter((x) => x.status === stage).length
        }}
        项</small
      ><ArrowRight v-if="index < stages.length - 1" :size="16" />
    </div>
  </div>
  <div class="info-strip">
    <Info
      :size="17"
    />证据就绪须有措施与附件。关闭时，改善结论需后续周期值验证；未恢复需填写说明。已关闭记录仅质控可重开。
  </div>
  <section class="card table-card">
    <div class="section-heading">
      <h3>改进任务</h3>
      <select v-model="filter" aria-label="改进状态">
        <option value="ALL">全部状态</option>
        <option v-for="stage in stages" :key="stage" :value="stage">
          {{ label(stage) }}
        </option>
      </select>
    </div>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>改进指标 / 来源</th>
            <th>责任人</th>
            <th>截止日期</th>
            <th>基线 → 演示目标</th>
            <th>最新周期值</th>
            <th>进度</th>
            <th>佐证</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in rows" :key="item.id">
            <td>
              <strong>{{ item.indicatorName }}</strong
              ><small class="cell-sub"
                >{{ label(item.origin) }} · {{ item.id }}</small
              >
            </td>
            <td>{{ item.owner || "待指派" }}</td>
            <td>{{ item.dueDate }}</td>
            <td class="numeric">
              {{ formatValue(item.baseline) }} → {{ formatValue(item.target) }}
            </td>
            <td class="numeric">{{ formatValue(item.latestValue) }}</td>
            <td><Badge :value="item.status" /></td>
            <td>
              <span class="inline-icon"
                ><Paperclip :size="13" />{{ item.evidenceCount }}</span
              >
            </td>
            <td>
              <button class="text-button" @click="open(item)">
                {{ canWrite ? "推进 / 详情" : "查看详情" }}
              </button>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-if="!rows.length" class="empty">
        <Activity :size="32" /><strong>暂无改进任务</strong>
        <p>从红灯指标发起改进，形成管理闭环。</p>
      </div>
    </div>
  </section>
  <Modal v-if="creating" title="发起持续改进" @close="creating = false"
    ><form @submit.prevent="saveCreate">
      <label class="field"
        >改进指标<select v-model="createForm.indicatorId" required>
          <option
            v-for="item in state!.indicators"
            :key="item.id"
            :value="item.id"
          >
            {{ item.name }}（{{ label(item.light) }}）
          </option>
        </select></label
      >
      <div class="form-grid">
        <label class="field"
          >责任人<input
            v-model="createForm.owner"
            required
            placeholder="填写改进责任人" /></label
        ><label class="field"
          >截止日期<input v-model="createForm.dueDate" type="date" required
        /></label>
      </div>
      <p class="hint">来源标记：红灯预警。后台将保存创建时的基线与演示目标。</p>
      <div class="form-actions">
        <button type="button" class="button" @click="creating = false">
          取消</button
        ><button
          class="button primary"
          :disabled="busy || !createForm.owner.trim()"
        >
          {{ busy ? "创建中…" : "创建改进单" }}
        </button>
      </div>
    </form></Modal
  >
  <Modal
    v-if="selected"
    :title="`${selected.indicatorName} · 持续改进`"
    wide
    @close="selectedId = ''"
    ><div class="detail-metrics">
      <div>
        <small>创建基线</small
        ><strong>{{ formatValue(selected.baseline, unit) }}</strong>
      </div>
      <div>
        <small>演示目标</small
        ><strong>{{ formatValue(selected.target, unit) }}</strong>
      </div>
      <div>
        <small>后续周期值</small
        ><strong>{{ formatValue(selected.latestValue, unit) }}</strong>
      </div>
    </div>
    <form @submit.prevent="save">
      <fieldset :disabled="!canWrite || selected.status === 'closed' || busy">
        <div class="form-grid">
          <label class="field"
            >责任人<input v-model="form.owner" required /></label
          ><label class="field"
            >截止日期<input v-model="form.dueDate" type="date" required
          /></label>
        </div>
        <label class="field"
          >改进措施与执行情况<textarea
            v-model="form.actionText"
            rows="4"
            placeholder="写明具体措施、执行情况与效果"
          ></textarea></label
        ><label class="field"
          >推进状态<select v-model="form.status">
            <option :value="selected.status">
              {{ label(selected.status) }}（保持当前）
            </option>
            <option v-if="next" :value="next">
              {{ label(next) }}（下一阶段）
            </option>
          </select></label
        ><template v-if="form.status === 'closed'"
          ><label class="field"
            >关闭结论<select v-model="form.closeMode">
              <option value="improved">已改善（需后续周期数据验证）</option>
              <option value="not_recovered">未恢复（说明原因后关闭）</option>
            </select></label
          ><label class="field"
            >关闭说明<textarea
              v-model="form.closeNote"
              :required="form.closeMode === 'not_recovered'"
              rows="3"
            ></textarea></label
        ></template>
      </fieldset>
      <p v-if="readyError" class="warning-text">
        进入待验证状态前，请填写改进措施并先上传至少一份佐证。
      </p>
      <EvidencePanel owner-type="improvement" :owner-id="selected.id" :read-only="selected.status === 'closed'" />
      <div class="form-actions">
        <button
          v-if="canWrite && selected.status === 'closed'"
          type="button"
          class="button"
          :disabled="busy"
          @click="reopen"
        >
          <RotateCcw :size="15" />重新打开</button
        ><button type="button" class="button" @click="selectedId = ''">
          关闭</button
        ><button
          v-if="canWrite && selected.status !== 'closed'"
          class="button primary"
          :disabled="
            busy ||
            readyError ||
            !form.owner.trim() ||
            (form.status === 'closed' &&
              form.closeMode === 'not_recovered' &&
              !form.closeNote.trim())
          "
        >
          {{ busy ? "保存中…" : "保存并推进" }}
        </button>
      </div>
    </form></Modal
  >
</template>
