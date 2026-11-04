<script setup lang="ts">
import { computed, ref } from "vue";
import { hasPermission } from "../auth";
import ObserveView from "./ObserveView.vue";
import IngestionView from "./IngestionView.vue";
import AlertView from "./AlertView.vue";
import ReportView from "./ReportView.vue";

const allTabs = [
  { key: "observe", label: "指标监测", component: ObserveView },
  { key: "ingestion", label: "数据接入", component: IngestionView },
  { key: "alert", label: "预警与整改", component: AlertView },
  { key: "report", label: "报告中心", component: ReportView },
];
const tabs = computed(() => allTabs.filter(tab => hasPermission(({"observe":"metric.read","ingestion":"batch.read","alert":"improvement.read","report":"report.read"} as Record<string,string>)[tab.key]!)));
const active = ref("observe");
const current = computed(() => tabs.value.find((tab) => tab.key === active.value) || tabs.value[0]);
</script>

<template>
  <div class="v3-shell">
    <div class="info-strip">
      <strong>v3 数据中台</strong>
      数据源 → 映射清洗 → 批次质量门 → 标准事实 → 受控公式 → 结果与预警 → 整改 → 快照报告。

      <span class="subtle-tag">公式受控</span>
    </div>
    <div class="tabs page-tabs" role="tablist" aria-label="v3 模块">
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
.v3-shell {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
</style>
