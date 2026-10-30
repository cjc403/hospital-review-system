<script setup lang="ts">
import { ref } from "vue";
import ClauseCenter from "./ClauseCenter.vue";
import TaskBoard from "./TaskBoard.vue";
import EvidenceLibrary from "./EvidenceLibrary.vue";
import DataBatches from "./DataBatches.vue";
import ScoreCenter from "./ScoreCenter.vue";

const tabs = [
  { key: "clause", label: "标准与条款", component: ClauseCenter },
  { key: "task", label: "任务台", component: TaskBoard },
  { key: "evidence", label: "材料库", component: EvidenceLibrary },
  { key: "batch", label: "数据批次", component: DataBatches },
  { key: "score", label: "试算与报告", component: ScoreCenter },
];
const active = ref("clause");
const current = tabs.find((tab) => tab.key === active.value) || tabs[0]!;
</script>

<template>
  <div class="v2-shell">
    <div class="info-strip">
      <strong>v0.2 生产导向骨架</strong>
      标准有版本、条款可分派、材料有版本、数据可追溯；权限与范围由后端强制，前端隐藏菜单不构成授权。
      <span class="subtle-tag">合成数据</span>
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
    <component :is="current.component" :key="active" />
  </div>
</template>

<style scoped>
.v2-shell {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
</style>
