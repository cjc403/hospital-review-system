<script setup lang="ts">
import { computed, ref } from "vue";
import {
  Search,
  ShieldCheck,
  LockKeyhole,
  Info,
  Paperclip,
} from "lucide-vue-next";
import { workspace as state, busy, canWrite, mutate } from "../store";
import type { Prereq } from "../types";
import Badge from "./Badge.vue";
import Modal from "./Modal.vue";
import EvidencePanel from "./EvidencePanel.vue";
const search = ref(""),
  filter = ref("ALL");
const rows = computed(() =>
  state.value!.prerequisites.filter(
    (x) =>
      `${x.title} ${x.code} ${x.owner}`.includes(search.value.trim()) &&
      (filter.value === "ALL" || x.compliance === filter.value),
  ),
);
const selectedId = ref("");
const selected = computed(() =>
  state.value!.prerequisites.find((x) => x.id === selectedId.value),
);
const form = ref({ compliance: "unknown", workflow: "draft", statement: "" });
function open(item: Prereq) {
  selectedId.value = item.id;
  form.value = {
    compliance: item.compliance,
    workflow: item.workflow,
    statement: item.statement,
  };
}
async function save() {
  if (
    selected.value &&
    (await mutate(
      `/prerequisites/${selected.value.id}`,
      form.value,
      "PUT",
      "前置条款自评已保存",
    ))
  )
    selectedId.value = "";
}
</script>
<template>
  <div class="page-intro">
    <div>
      <h2>前置自评</h2>
      <p>逐条完成前置要求自评、材料归档与质控复核。</p>
    </div>
    <span class="subtle-tag">{{ state!.prerequisites.length }} 项前置要求</span>
  </div>
  <div class="stats-strip">
    <div>
      <strong class="positive">{{
        state!.prerequisites.filter((x) => x.compliance === "compliant").length
      }}</strong
      ><span>已确认符合</span>
    </div>
    <div>
      <strong class="negative">{{
        state!.prerequisites.filter((x) => x.compliance === "non_compliant")
          .length
      }}</strong
      ><span>不符合</span>
    </div>
    <div>
      <strong>{{
        state!.prerequisites.filter((x) => x.compliance === "unknown").length
      }}</strong
      ><span>待确认</span>
    </div>
    <div>
      <strong>{{
        state!.prerequisites.filter((x) => x.workflow === "accepted").length
      }}</strong
      ><span>已完成复核</span>
    </div>
  </div>
  <div class="info-strip">
    <Info
      :size="17"
    />自评结论与审核进度独立维护。待确认不代表符合；系统数据真实性条款由最新抽查自动驱动，不能人工修改。
  </div>
  <section class="card table-card">
    <div class="section-heading">
      <h3>条款自评清单</h3>
      <div class="inline-filters">
        <div class="search-box compact">
          <Search :size="15" /><input
            v-model="search"
            placeholder="搜索条款或负责人"
            aria-label="搜索前置条款"
          />
        </div>
        <select v-model="filter" aria-label="前置结论">
          <option value="ALL">全部结论</option>
          <option value="compliant">符合</option>
          <option value="non_compliant">不符合</option>
          <option value="unknown">待确认</option>
        </select>
      </div>
    </div>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>条款编号</th>
            <th>前置要求</th>
            <th>责任部门</th>
            <th>自评结论</th>
            <th>审核进度</th>
            <th>佐证</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in rows" :key="item.id">
            <td class="code-label">{{ item.code }}</td>
            <td class="wide-cell">
              <strong>{{ item.title }}</strong
              ><small class="cell-sub"
                >{{ item.section
                }}<span v-if="item.systemGenerated"> · 系统生成</span></small
              >
            </td>
            <td>{{ item.owner }}</td>
            <td><Badge :value="item.compliance" /></td>
            <td><Badge :value="item.workflow" /></td>
            <td>
              <span class="inline-icon"
                ><Paperclip :size="13" />{{ item.evidenceCount }}</span
              >
            </td>
            <td>
              <button class="text-button" @click="open(item)">
                {{
                  canWrite && !item.systemGenerated ? "自评 / 复核" : "查看详情"
                }}
              </button>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-if="!rows.length" class="empty">暂无符合条件的前置要求</div>
    </div>
  </section>
  <Modal
    v-if="selected"
    :title="`${selected.code} · 前置条款自评`"
    wide
    @close="selectedId = ''"
    ><h3 class="detail-title">{{ selected.title }}</h3>
    <div v-if="selected.systemGenerated" class="info-strip">
      <LockKeyhole
        :size="17"
      />本条由条款抽查自动生成结论。请到“数据核查”发起新抽查。
    </div>
    <form @submit.prevent="save">
      <fieldset :disabled="!canWrite || selected.systemGenerated || busy">
        <div class="form-grid">
          <label class="field"
            >自评结论<select v-model="form.compliance">
              <option value="unknown">待确认</option>
              <option value="compliant">符合</option>
              <option value="non_compliant">不符合</option>
            </select></label
          ><label class="field"
            >审核进度<select v-model="form.workflow">
              <option value="draft">草稿</option>
              <option value="submitted">提交审核</option>
              <option value="accepted">已复核</option>
              <option value="rejected">退回补充</option>
            </select></label
          >
        </div>
        <label class="field"
          >自评与复核说明<textarea
            v-model="form.statement"
            :required="form.compliance === 'non_compliant'"
            rows="4"
            placeholder="描述条款落实情况、评估依据及不足"
          ></textarea>
        </label>
      </fieldset>
      <p v-if="form.compliance === 'non_compliant'" class="warning-text">
        不符合结论须填写说明并先上传至少一份佐证。
      </p>
      <EvidencePanel :owner-type="'prereq'" :owner-id="selected.id" />
      <div class="form-actions">
        <button type="button" class="button" @click="selectedId = ''">
          关闭</button
        ><button
          v-if="canWrite && !selected.systemGenerated"
          class="button primary"
          :disabled="
            busy ||
            (form.compliance === 'non_compliant' &&
              (!form.statement.trim() || !selected.evidenceCount))
          "
        >
          {{ busy ? "保存中…" : "保存自评与进度" }}
        </button>
      </div>
    </form></Modal
  >
</template>
