<script setup lang="ts">
import { currentUser, hasPermission } from "../auth";
import { computed, ref } from "vue";
import { Plus, Info, ClipboardCheck } from "lucide-vue-next";
import { workspace as state, busy, canWrite, mutate } from "../store";
import { formatDate, formatValue } from "../domain";
import type { Manual } from "../types";
import Badge from "./Badge.vue";
import Modal from "./Modal.vue";
const modal = ref(false);
const form = ref({
  indicatorId: "",
  deptCode: currentUser.value?.deptCode || "ALL",
  period: "2026-09",
  value: 0,
  note: "",
});
const filter = ref("ALL");
const rows = computed(() =>
  [...state.value!.manualValues]
    .reverse()
    .filter((x) => filter.value === "ALL" || x.status === filter.value),
);
const selected = ref<Manual | null>(null);
const decision = ref("approved");
const reviewNote = ref("");
function create() {
  form.value = {
    indicatorId: state.value!.indicators[0]?.id || "",
    deptCode: currentUser.value?.deptCode || "ALL",
    period: state.value!.meta.period,
    value: 0,
    note: "",
  };
  modal.value = true;
}
async function submit() {
  if (
    await mutate(
      "/manual-values",
      form.value,
      "POST",
      "补录已提交，等待质控审核",
    )
  )
    modal.value = false;
}
function review(item: Manual) {
  selected.value = item;
  reviewNote.value = "";
  decision.value = "approved";
}
async function saveReview() {
  if (
    selected.value &&
    (await mutate(
      `/manual-values/${selected.value.id}/review`,
      { decision: decision.value, note: reviewNote.value },
      "POST",
      decision.value === "approved"
        ? "审核通过，已生成独立人工批次"
        : "补录已退回",
    ))
  )
    selected.value = null;
}
</script>
<template>
  <div class="page-intro">
    <div>
      <h2>补录审核</h2>
      <p>补充指标人工数据，保留自动原值与审核轨迹。</p>
    </div>
    <button
      v-if="hasPermission('manual.submit')"
      class="button primary"
      :disabled="busy"
      @click="create"
    >
      <Plus :size="16" />新增补录
    </button>
  </div>
  <div class="info-strip">
    <Info
      :size="17"
    />审核通过后生成独立人工批次并标记覆盖来源。自动原值与历史批次保留，已审核记录不可重复审核。
  </div>
  <div class="stats-strip">
    <div>
      <strong>{{
        state!.manualValues.filter((x) => x.status === "submitted").length
      }}</strong
      ><span>待审核</span>
    </div>
    <div>
      <strong>{{
        state!.manualValues.filter((x) => x.status === "approved").length
      }}</strong
      ><span>已通过</span>
    </div>
    <div>
      <strong>{{
        state!.manualValues.filter((x) => x.status === "rejected").length
      }}</strong
      ><span>已退回</span>
    </div>
  </div>
  <section class="card table-card">
    <div class="section-heading">
      <h3>补录申请</h3>
      <select v-model="filter" aria-label="审核状态">
        <option value="ALL">全部状态</option>
        <option value="submitted">待审核</option>
        <option value="approved">已通过</option>
        <option value="rejected">已退回</option>
      </select>
    </div>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>指标</th>
            <th>周期 / 科室</th>
            <th>补录值</th>
            <th>补录说明</th>
            <th>提交人 / 时间</th>
            <th>状态</th>
            <th>审核人</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in rows" :key="item.id">
            <td>
              <strong>{{ item.indicatorName }}</strong>
            </td>
            <td>
              {{ item.period
              }}<small class="cell-sub">{{
                item.deptCode === "ALL"
                  ? "全院"
                  : state!.departments.find((d) => d.code === item.deptCode)
                      ?.name || item.deptCode
              }}</small>
            </td>
            <td class="numeric">
              {{
                formatValue(
                  item.value,
                  state!.indicators.find((i) => i.id === item.indicatorId)
                    ?.unit,
                )
              }}
            </td>
            <td class="truncate-cell" :title="item.note">{{ item.note }}</td>
            <td>
              {{ item.submitter
              }}<small class="cell-sub">{{
                formatDate(item.submittedAt)
              }}</small>
            </td>
            <td><Badge :value="item.status" /></td>
            <td>{{ item.reviewer || "—" }}</td>
            <td>
              <button
                v-if="canWrite && item.status === 'submitted'"
                class="text-button"
                :disabled="busy"
                @click="review(item)"
              >
                审核
              </button>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-if="!rows.length" class="empty">
        <ClipboardCheck :size="30" /><strong>暂无补录申请</strong>
        <p>质控管理员可新增补录并完成审核。</p>
      </div>
    </div>
  </section>
  <Modal v-if="modal" title="新增人工补录" @close="modal = false"
    ><form @submit.prevent="submit">
      <label class="field"
        >补录指标<select v-model="form.indicatorId" required>
          <option
            v-for="item in state!.indicators"
            :key="item.id"
            :value="item.id"
          >
            {{ item.name }}（{{ item.unit }}）
          </option>
        </select></label
      >
      <div class="form-grid">
        <label class="field"
          >数据周期<input v-model="form.period" type="month" required /></label
        ><label class="field"
          >所属科室<input :value="currentUser?.deptName || '全院'" readonly aria-label="补录范围" />
          <small>按当前账号授权范围提交</small></label
        >
      </div>
      <label class="field"
        >补录值 <small>按指标原始单位填写，百分比 0.35 表示 0.35%</small
        ><input
          v-model.number="form.value"
          type="number"
          step="any"
          min="0"
          required /></label
      ><label class="field"
        >补录说明<textarea
          v-model="form.note"
          required
          rows="4"
          placeholder="请说明数据来源、补录原因和核实依据"
        ></textarea>
      </label>
      <div class="form-actions">
        <button type="button" class="button" @click="modal = false">取消</button
        ><button class="button primary" :disabled="busy || !form.note.trim()">
          {{ busy ? "提交中…" : "提交审核" }}
        </button>
      </div>
    </form></Modal
  >
  <Modal v-if="selected" title="审核人工补录" @close="selected = null"
    ><form @submit.prevent="saveReview">
      <div class="review-summary">
        <h3>{{ selected.indicatorName }}</h3>
        <strong>{{
          formatValue(
            selected.value,
            state!.indicators.find((i) => i.id === selected!.indicatorId)?.unit,
          )
        }}</strong>
        <p>{{ selected.note }}</p>
      </div>
      <label class="field"
        >审核结论<select v-model="decision">
          <option value="approved">通过并生成批次</option>
          <option value="rejected">退回补充</option>
        </select></label
      ><label class="field"
        >审核意见<textarea
          v-model="reviewNote"
          required
          rows="3"
          placeholder="记录审核依据或退回原因"
        ></textarea>
      </label>
      <div class="form-actions">
        <button type="button" class="button" @click="selected = null">
          取消</button
        ><button class="button primary" :disabled="busy || !reviewNote.trim()">
          {{ busy ? "审核中…" : "确认审核" }}
        </button>
      </div>
    </form></Modal
  >
</template>
