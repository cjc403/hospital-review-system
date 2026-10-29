<script setup lang="ts">
import { computed, ref } from "vue";
import {
  FileText,
  Plus,
  LockKeyhole,
  CalendarDays,
  Info,
  Search,
} from "lucide-vue-next";
import { workspace as state, role, busy, canWrite, mutate } from "../store";
import type { Policy } from "../types";
import Badge from "./Badge.vue";
import Modal from "./Modal.vue";
const creating = ref(false),
  selected = ref<Policy | null>(null),
  search = ref("");
const allowed = computed(() => canWrite.value || role.value === "information");
const form = ref({
  code: "",
  title: "",
  kind: "national_standard",
  publishedOn: "2026-09-01",
  effectiveOn: "2026-10-01",
  impacts: [] as string[],
  note: "",
});
const rows = computed(() =>
  state.value!.policies.filter((x) =>
    `${x.title} ${x.code}`.includes(search.value.trim()),
  ),
);
const impactLabels: Record<string, string> = {
  clause: "评审条款",
  indicator_def: "指标定义",
  code_set: "编码集合",
  selection_set: "纳入规则",
  scoring_rule: "计分规则",
};
const kindLabels: Record<string, string> = {
  national_standard: "国家标准",
  interpretation: "政策解读",
  indicator_pack: "指标包",
  reference: "参考文件",
};
function openCreate() {
  form.value = {
    code: "",
    title: "",
    kind: "national_standard",
    publishedOn: "2026-09-01",
    effectiveOn: "2026-10-01",
    impacts: [],
    note: "",
  };
  creating.value = true;
}
async function save() {
  if (await mutate("/policies", form.value, "POST", "政策登记元数据已保存"))
    creating.value = false;
}
async function confirm() {
  if (
    selected.value &&
    (await mutate(
      `/policies/${selected.value.id}/confirm`,
      {},
      "POST",
      "政策影响已确认",
    ))
  )
    selected.value = null;
}
</script>
<template>
  <div class="page-intro">
    <div>
      <h2>政策文件</h2>
      <p>统一登记政策版本、生效日期与业务影响，保持规则来源可追溯。</p>
    </div>
    <button
      v-if="allowed"
      class="button primary"
      :disabled="busy"
      @click="openCreate"
    >
      <Plus :size="16" />登记政策
    </button>
  </div>
  <div class="info-strip">
    <Info
      :size="17"
    />本地演示仅管理政策元数据与影响说明。涉及计分规则的变更将阻断确认，原始政策与参考文件保持留存。
  </div>
  <section class="card">
    <div class="section-heading">
      <h3>
        政策登记库 <span class="count">{{ rows.length }}</span>
      </h3>
      <div class="search-box compact">
        <Search :size="15" /><input
          v-model="search"
          placeholder="搜索文件名称或文号"
          aria-label="搜索政策"
        />
      </div>
    </div>
    <div class="policy-grid">
      <article v-for="item in rows" :key="item.id" class="policy-card">
        <div class="policy-top">
          <span class="policy-file-icon"><FileText :size="24" /></span
          ><Badge :value="item.status" />
        </div>
        <div class="eyebrow">{{ item.code }} · v{{ item.version }}</div>
        <h3>{{ item.title }}</h3>
        <p class="policy-kind">{{ kindLabels[item.kind] || item.kind }}</p>
        <div class="policy-dates">
          <CalendarDays :size="14" />发布 {{ item.publishedOn
          }}<span>生效 {{ item.effectiveOn }}</span>
        </div>
        <div class="impact-tags">
          <span
            v-for="impact in item.impacts"
            :key="impact"
            :class="{ blocked: impact === 'scoring_rule' }"
            >{{ impactLabels[impact] || impact }}</span
          >
        </div>
        <p class="policy-note">{{ item.note || "暂无影响说明" }}</p>
        <button class="text-button" @click="selected = item">
          查看登记与影响说明 <span>→</span>
        </button>
      </article>
    </div>
    <div v-if="!rows.length" class="empty">
      <FileText :size="30" /><strong>暂无匹配的政策登记</strong>
    </div>
  </section>
  <Modal v-if="creating" title="登记政策元数据" @close="creating = false"
    ><form @submit.prevent="save">
      <div class="form-grid">
        <label class="field"
          >文件文号<input
            v-model="form.code"
            required
            placeholder="如：院质控〔2026〕12号" /></label
        ><label class="field"
          >文件类别<select v-model="form.kind">
            <option value="national_standard">国家标准</option>
            <option value="interpretation">政策解读</option>
            <option value="indicator_pack">指标包</option>
            <option value="reference">参考文件</option>
          </select></label
        >
      </div>
      <label class="field"
        >文件标题<input
          v-model="form.title"
          required
          placeholder="填写政策或制度全称"
      /></label>
      <div class="form-grid">
        <label class="field"
          >发布日期<input
            v-model="form.publishedOn"
            type="date"
            required /></label
        ><label class="field"
          >生效日期<input v-model="form.effectiveOn" type="date" required
        /></label>
      </div>
      <div class="field">
        影响范围
        <div class="checkbox-group">
          <label
            v-for="(text, value) in {
              clause: '评审条款',
              indicator_def: '指标定义',
              code_set: '编码集合',
              selection_set: '纳入规则',
              scoring_rule: '计分规则',
            }"
            :key="value"
            ><input v-model="form.impacts" type="checkbox" :value="value" />{{
              text
            }}</label
          >
        </div>
      </div>
      <label class="field"
        >影响说明<textarea
          v-model="form.note"
          required
          rows="4"
          placeholder="描述影响的指标、口径或工作流程"
        ></textarea>
      </label>
      <p v-if="form.impacts.includes('scoring_rule')" class="warning-text">
        涉及计分规则的记录将标记阻断，禁止直接确认生效。
      </p>
      <div class="form-actions">
        <button type="button" class="button" @click="creating = false">
          取消</button
        ><button
          class="button primary"
          :disabled="busy || !form.title.trim() || !form.note.trim()"
        >
          {{ busy ? "登记中…" : "保存登记" }}
        </button>
      </div>
    </form></Modal
  >
  <Modal v-if="selected" title="政策登记详情" @close="selected = null"
    ><div class="detail-heading">
      <span class="code-label">{{ selected.code }}</span
      ><Badge :value="selected.status" />
    </div>
    <h3 class="detail-title">{{ selected.title }}</h3>
    <dl class="definition-list">
      <dt>文件类型 / 版本</dt>
      <dd>
        {{ kindLabels[selected.kind] || selected.kind }} / v{{
          selected.version
        }}
      </dd>
      <dt>发布 / 生效日期</dt>
      <dd>{{ selected.publishedOn }} / {{ selected.effectiveOn }}</dd>
      <dt>影响范围</dt>
      <dd>
        {{
          selected.impacts.map((x) => impactLabels[x] || x).join("、") ||
          "未标注"
        }}
      </dd>
      <dt>影响说明</dt>
      <dd>{{ selected.note || "暂无说明" }}</dd>
    </dl>
    <div v-if="selected.impacts.includes('scoring_rule')" class="warning-strip">
      <LockKeyhole :size="18" />
      <p>该记录涉及计分规则，禁止直接确认。演示系统不提供正式规则发布流程。</p>
    </div>
    <div class="form-actions">
      <button class="button" @click="selected = null">关闭</button
      ><button
        v-if="canWrite"
        class="button primary"
        :disabled="
          busy ||
          selected.impacts.includes('scoring_rule') ||
          ['confirmed', 'active', 'blocked'].includes(selected.status)
        "
        @click="confirm"
      >
        {{ busy ? "确认中…" : "确认政策影响" }}
      </button>
    </div></Modal
  >
</template>
