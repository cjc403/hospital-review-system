<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from "vue";
import {
  init,
  use,
  type EChartsCoreOption,
  type EChartsType,
} from "echarts/core";
import { LineChart, BarChart, PieChart } from "echarts/charts";
import {
  GridComponent,
  TooltipComponent,
  LegendComponent,
  MarkLineComponent,
} from "echarts/components";
import { CanvasRenderer } from "echarts/renderers";
use([
  LineChart,
  BarChart,
  PieChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  MarkLineComponent,
  CanvasRenderer,
]);
const props = defineProps<{ option: EChartsCoreOption; height?: number }>();
const element = ref<HTMLElement>();
let chart: EChartsType | undefined;
let observer: ResizeObserver | undefined;
onMounted(() => {
  chart = init(element.value!, undefined, { renderer: "canvas" });
  chart.setOption(props.option);
  observer = new ResizeObserver(() => chart?.resize());
  observer.observe(element.value!);
});
watch(
  () => props.option,
  (option) => chart?.setOption(option, true),
  { deep: true },
);
onBeforeUnmount(() => {
  observer?.disconnect();
  chart?.dispose();
});
</script>
<template>
  <div
    ref="element"
    class="chart"
    role="img"
    aria-label="指标统计图表，详细数值见相邻表格"
    :style="{ height: `${height || 280}px` }"
  ></div>
</template>
