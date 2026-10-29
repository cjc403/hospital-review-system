<script setup lang="ts">
import { computed } from "vue";
import {
  Activity,
  ArrowUpRight,
  ArrowRight,
  ShieldCheck,
  ClipboardCheck,
  CircleAlert,
  Layers,
  ChevronRight,
} from "lucide-vue-next";
import { workspace as state } from "../store";
import { formatDate, formatValue } from "../domain";
import Badge from "./Badge.vue";
import Chart from "./Chart.vue";
const emit = defineEmits<{ navigate: [page: string] }>();
const w = computed(() => state.value!);
const green = computed(
  () => w.value.indicators.filter((x) => x.light === "green").length,
);
const alerts = computed(() =>
  w.value.indicators.filter((x) => x.light === "red" || x.light === "yellow"),
);
const latestScore = computed(() => w.value.scoreRuns.at(-1));
const trend = computed(() => {
  const indicators = w.value.indicators;
  const periods = [
    ...new Set(indicators.flatMap((x) => x.trends.map((t) => t.period))),
  ]
    .sort()
    .slice(-12);
  return {
    color: ["#2274ed", "#23b0a7"],
    tooltip: { trigger: "axis" },
    legend: {
      right: 0,
      top: 0,
      icon: "circle",
      itemWidth: 8,
      textStyle: { color: "#758399" },
    },
    grid: { left: 38, right: 12, top: 45, bottom: 28 },
    xAxis: {
      type: "category",
      data: periods.map((x) => x.slice(5) + "月"),
      boundaryGap: false,
      axisLine: { lineStyle: { color: "#e3e9f1" } },
      axisTick: { show: false },
      axisLabel: { color: "#8a96a8" },
    },
    yAxis: {
      type: "value",
      max: indicators.length,
      minInterval: 1,
      axisLabel: { color: "#8a96a8" },
      splitLine: { lineStyle: { color: "#eef2f6", type: "dashed" } },
    },
    series: [
      {
        name: "达标指标",
        type: "line",
        smooth: true,
        symbolSize: 6,
        data: periods.map(
          (p) =>
            indicators.filter((i) => {
              const value = i.trends.find((t) => t.period === p)?.value;
              return (
                value != null &&
                (i.direction === "high" ? value >= i.target : value <= i.target)
              );
            }).length,
        ),
        areaStyle: { color: "#2274ed", opacity: 0.07 },
      },
      {
        name: "已采集指标",
        type: "line",
        smooth: true,
        symbolSize: 5,
        lineStyle: { type: "dashed", width: 2 },
        data: periods.map(
          (p) =>
            indicators.filter(
              (i) => i.trends.find((t) => t.period === p)?.value != null,
            ).length,
        ),
      },
    ],
  };
});
const chapters = computed(() => {
  const names = [...new Set(w.value.indicators.map((x) => x.chapter))];
  return names.map((name) => {
    const items = w.value.indicators.filter((x) => x.chapter === name);
    return {
      name,
      all: items.length,
      green: items.filter((x) => x.light === "green").length,
    };
  });
});
const todos = computed(() => [
  {
    label: "补录数据待审核",
    count: w.value.manualValues.filter((x) => x.status === "submitted").length,
    desc: "核验说明后生成独立人工批次",
    page: "manual",
    icon: ClipboardCheck,
    color: "blue",
  },
  {
    label: "前置条款待确认",
    count: w.value.prerequisites.filter((x) => x.compliance === "unknown")
      .length,
    desc: "完成自评及佐证资料复核",
    page: "prerequisites",
    icon: ShieldCheck,
    color: "teal",
  },
  {
    label: "持续改进进行中",
    count: w.value.improvements.filter((x) => x.status !== "closed").length,
    desc: "跟踪责任人、措施与验证结果",
    page: "improvements",
    icon: Activity,
    color: "orange",
  },
]);
</script>
<template>
  <div class="welcome card">
    <div>
      <div class="eyebrow">
        <span class="live-dot"></span>质量管理 · 全院协同
      </div>
      <h2>让每一次评审，都有据可循。</h2>
      <p>汇聚指标监测、数据核查与持续改进，掌握医院评审准备进展。</p>
      <div class="welcome-tags">
        <span><ShieldCheck :size="14" />{{ w.meta.standard }}</span
        ><span><Layers :size="14" />{{ w.meta.period }} 监测周期</span>
      </div>
    </div>
    <div class="welcome-summary">
      <div class="mini-grid">
        <span></span><span></span><span></span><span></span>
      </div>
      <p>评审准备工作台</p>
      <button class="text-button" @click="emit('navigate', 'score')">
        进入模拟评审 <ArrowRight :size="16" />
      </button>
    </div>
  </div>
  <div class="metric-grid">
    <article class="metric-card">
      <span class="metric-icon blue"><Layers :size="21" /></span>
      <div class="metric-label">监测样例指标</div>
      <div class="metric-number">
        {{ w.indicators.length }}<small>项</small>
      </div>
      <div class="metric-foot">
        覆盖 {{ chapters.length }} 个监测章节
        <span class="muted">/ 共 {{ w.meta.totalMonitoringClauses }} 条</span>
      </div>
    </article>
    <article class="metric-card">
      <span class="metric-icon teal"><ShieldCheck :size="21" /></span>
      <div class="metric-label">本期达标率</div>
      <div class="metric-number">
        {{
          w.indicators.length
            ? ((green / w.indicators.length) * 100).toFixed(1)
            : "—"
        }}<small>%</small>
      </div>
      <div class="metric-foot">
        <span class="positive">{{ green }} 项达标</span
        ><span class="muted"> / 样例指标口径</span>
      </div>
    </article>
    <article class="metric-card">
      <span class="metric-icon orange"><CircleAlert :size="21" /></span>
      <div class="metric-label">重点关注指标</div>
      <div class="metric-number">{{ alerts.length }}<small>项</small></div>
      <button
        class="metric-foot text-button"
        @click="emit('navigate', 'indicators')"
      >
        查看预警与趋势 <ArrowUpRight :size="14" />
      </button>
    </article>
    <article class="metric-card">
      <span class="metric-icon purple"><ClipboardCheck :size="21" /></span>
      <div class="metric-label">前置要求已复核</div>
      <div class="metric-number">
        {{ w.prerequisites.filter((x) => x.workflow === "accepted").length
        }}<small>/ {{ w.prerequisites.length }}</small>
      </div>
      <div class="metric-foot">
        {{
          w.prerequisites.filter((x) => x.compliance === "non_compliant").length
        }}
        项不符合 · {{ latestScore ? "已有样例试算" : "尚未执行试算" }}
      </div>
    </article>
  </div>
  <div class="dashboard-main">
    <section class="card">
      <div class="section-heading">
        <div>
          <h3>监测趋势</h3>
          <p class="hint">近 12 个月 · 依据各期样例指标演示目标统计</p>
        </div>
        <span class="subtle-tag">全院视角</span>
      </div>
      <Chart :option="trend" :height="260" />
    </section>
    <section class="card">
      <div class="section-heading">
        <div>
          <h3>章节达标概览</h3>
          <p class="hint">本期样例覆盖情况</p>
        </div>
        <span class="legend-dot">达标 / 全部</span>
      </div>
      <div class="chapter-list">
        <div
          v-for="(chapter, index) in chapters"
          :key="chapter.name"
          class="chapter-row"
        >
          <div class="chapter-label">
            <span class="chapter-index">0{{ index + 1 }}</span
            ><strong>{{ chapter.name }}</strong
            ><span
              >{{ chapter.green }}<small> / {{ chapter.all }}</small></span
            >
          </div>
          <div class="progress-track">
            <div
              :style="{ width: `${(chapter.green / chapter.all) * 100}%` }"
            ></div>
          </div>
        </div>
      </div>
    </section>
  </div>
  <div class="dashboard-bottom">
    <section class="card">
      <div class="section-heading">
        <div>
          <h3><span class="title-mark"></span>重点关注</h3>
          <p class="hint">优先跟进红灯与临界指标，推进质量改进</p>
        </div>
        <button class="text-button" @click="emit('navigate', 'indicators')">
          全部指标<ChevronRight :size="15" />
        </button>
      </div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>指标名称</th>
              <th>本期值</th>
              <th>演示目标</th>
              <th>状态</th>
              <th>责任部门</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in alerts.slice(0, 4)" :key="item.id">
              <td>
                <strong>{{ item.name }}</strong
                ><small class="cell-sub">{{ item.code }}</small>
              </td>
              <td class="numeric">{{ formatValue(item.value, item.unit) }}</td>
              <td>
                {{ item.direction === "high" ? "≥" : "≤" }}
                {{ formatValue(item.target, item.unit) }}
              </td>
              <td><Badge :value="item.light" /></td>
              <td>{{ item.owner }}</td>
            </tr>
          </tbody>
        </table>
        <div v-if="!alerts.length" class="empty">本期暂无预警指标</div>
      </div>
    </section>
    <section class="card todo-card">
      <div class="section-heading">
        <h3>我的待办</h3>
        <span class="subtle-tag">协同处理</span>
      </div>
      <button
        v-for="todo in todos"
        :key="todo.page"
        class="todo-row"
        @click="emit('navigate', todo.page)"
      >
        <span class="todo-icon" :class="todo.color"
          ><component :is="todo.icon" :size="19"
        /></span>
        <div class="grow">
          <strong>{{ todo.label }}</strong
          ><small>{{ todo.desc }}</small>
        </div>
        <b>{{ todo.count }}</b
        ><ChevronRight :size="15" />
      </button>
      <div class="data-timestamp">
        <span class="live-dot"></span>数据更新至
        {{ formatDate(w.meta.dataAsOf) }}
      </div>
    </section>
  </div>
  <div class="footnote">
    本工作台使用合成数据。当前样例未覆盖
    {{ w.meta.totalMonitoringClauses }} 条完整监测条款及 60%
    覆盖要求，所有等次均为演示试算。
  </div>
</template>
