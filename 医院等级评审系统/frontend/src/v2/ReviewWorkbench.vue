<script setup lang="ts">
import { computed, ref } from "vue";
import { hasPermission } from "../auth";
import ClauseCenter from "./ClauseCenter.vue";
import TaskBoard from "./TaskBoard.vue";
import EvidenceLibrary from "./EvidenceLibrary.vue";
import DataBatches from "./DataBatches.vue";
import PrereqAudit from "./PrereqAudit.vue";
import ScoreCenter from "./ScoreCenter.vue";

const allTabs = [
  { key: "clause", label: "标准与条款", component: ClauseCenter },
  { key: "task", label: "任务台", component: TaskBoard },
  { key: "evidence", label: "材料库", component: EvidenceLibrary },
  { key: "batch", label: "数据批次", component: DataBatches },
  { key: "assess", label: "前置与抽查", component: PrereqAudit },
  { key: "score", label: "试算与报告", component: ScoreCenter },
];
const tabs = computed(() => allTabs.filter(tab => hasPermission(({"clause":"standard.read","task":"task.read","evidence":"evidence.read","batch":"batch.read","assess":"assessment.read","score":"assessment.read"} as Record<string,string>)[tab.key]!)));
const active = ref("clause");
const current = computed(() => tabs.value.find((tab) => tab.key === active.value) || tabs.value[0]);
</script>

<template>
  <div class="v2-shell">
    <div class="info-strip">
      <strong>评审作业中心</strong>
      标准有版本、条款可分派、材料有版本、数据可追溯；权限与范围由后端强制，前端隐藏菜单不构成授权。

    </div>
    <div class="tabs page-tabs" role="tablist" aria-label="v0.2 模块">
      <button
        v-for="tab in tabs"
        :key="tab.key"
        :class="{ active: active === tab.key }"
        @click="active = tab.key"
      >
        {{ tab.label }}
      </button>
    </div>
    <component v-if="current" :is="current.component" :key="current.key" />
  </div>
</template>

<style scoped>
.v2-shell {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
</style>
