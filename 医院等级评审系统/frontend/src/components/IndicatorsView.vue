<script setup lang="ts">
import { computed, ref, watch } from "vue";
import {
  Search,
  SlidersHorizontal,
  ChevronRight,
  Download,
  Info,
  TrendingUp,
  Users,
  BookOpen,
  ArrowLeft,
  ArrowRight,
} from "lucide-vue-next";
import { workspace as state, role, notify, loadWorkspace } from "../store";
import { request } from "../api";
import { filterIndicators, formatValue, label } from "../domain";
import type { Indicator, MemberPage } from "../types";
import Badge from "./Badge.vue";
import Chart from "./Chart.vue";
import Modal from "./Modal.vue";
const filters = ref({ keyword: "", chapter: "ALL", light: "ALL", mode: "ALL" });
const page = ref(1);
const filtered = computed(() =>
  filterIndicators(state.value!.indicators, filters.value),
);
const visible = computed(() =>
  filtered.value.slice((page.value - 1) * 10, page.value * 10),
);
watch(filters, () => (page.value = 1), { deep: true });
const chapters = computed(() => [
  ...new Set(state.value!.indicators.map((x) => x.chapter)),
]);
const selectedId = ref("");
const selected = computed(() =>
  state.value!.indicators.find((x) => x.id === selectedId.value),
);
const detailTab = ref("trend");
const department = ref(role.value === "department" ? "CARD" : "ALL");
const memberRole = ref("denominator");
const members = ref<MemberPage | null>(null);
const membersLoading = ref(false);
const membersError = ref("");
const memberPage = ref(1);
let memberGeneration = 0;
const canMembers = computed(
  () => role.value === "quality" || role.value === "department",
);
function open(item: Indicator) {
  selectedId.value = item.id;
  detailTab.value = "trend";
  memberPage.value = 1;
  members.value = null;
}
const trend = computed(() => {
  const item = selected.value;
  return {
    color: ["#2274ed"],
    tooltip: {
      trigger: "axis",
      valueFormatter: (v: number) => formatValue(v, item?.unit),
    },
    grid: { left: 50, right: 18, bottom: 36, top: 25 },
    xAxis: {
      type: "category",
      boundaryGap: false,
      data: item?.trends.map((x) => x.period),
      axisLabel: { color: "#8a96a8" },
      axisLine: { lineStyle: { color: "#e4eaf2" } },
    },
    yAxis: {
      type: "value",
      scale: true,
      splitLine: { lineStyle: { color: "#edf1f6", type: "dashed" } },
    },
    series: [
      {
        type: "line",
        smooth: true,
        symbolSize: 7,
        data: item?.trends.map((x) => x.value),
        areaStyle: { opacity: 0.06 },
        markLine: {
          symbol: "none",
          label: { formatter: "演示目标 {c}" },
          lineStyle: { color: "#21a894", type: "dashed" },
          data: [{ yAxis: item?.target }],
        },
      },
    ],
  };
});
async function loadMembers() {
  if (!selected.value || !canMembers.value || detailTab.value !== "members")
    return;
  const generation = ++memberGeneration;
  membersLoading.value = true;
  membersError.value = "";
  try {
    const params = new URLSearchParams({
      dept: department.value,
      role: memberRole.value,
      page: String(memberPage.value),
      size: "20",
    });
    const result = await request<MemberPage>(
      `/indicators/${selected.value.id}/members?${params}`,
      role.value,
    );
    if (generation === memberGeneration) members.value = result;
    // 成员查看写入审计，刷新审计而不改变成员列表。
    await loadWorkspace();
  } catch (e) {
    if (generation === memberGeneration) {
      members.value = null;
      membersError.value = (e as Error).message;
    }
  } finally {
    if (generation === memberGeneration) membersLoading.value = false;
  }
}
watch([detailTab, department, memberRole, memberPage], loadMembers);
function changeDept() {
  memberPage.value = 1;
}
</script>
<template>
  <div class="page-intro">
    <div>
      <h2>指标监测</h2>
      <p>从指标到科室，再到合成成员明细，逐层查看数据依据。</p>
    </div>
    <span class="subtle-tag"
      >{{ state!.meta.period }} · {{ state!.indicators.length }} 项样例</span
    >
  </div>
  <div class="card filter-card">
    <div class="search-box">
      <Search :size="17" /><input
        v-model="filters.keyword"
        placeholder="搜索指标名称、编码或责任部门"
        aria-label="搜索指标"
      />
    </div>
    <label class="filter-label"
      >监测章节<select v-model="filters.chapter">
        <option value="ALL">全部章节</option>
        <option v-for="chapter in chapters" :key="chapter">
          {{ chapter }}
        </option>
      </select></label
    ><label class="filter-label"
      >状态<select v-model="filters.light">
        <option value="ALL">全部状态</option>
        <option value="red">红灯预警</option>
        <option value="yellow">黄灯关注</option>
        <option value="green">绿色达标</option>
        <option value="na">待采集</option>
      </select></label
    ><label class="filter-label"
      >采集方式<select v-model="filters.mode">
        <option value="ALL">全部方式</option>
        <option value="auto">自动采集</option>
        <option value="manual">人工补录</option>
      </select></label
    ><button
      class="text-button"
      @click="
        filters = { keyword: '', chapter: 'ALL', light: 'ALL', mode: 'ALL' }
      "
    >
      <SlidersHorizontal :size="15" />重置
    </button>
  </div>
  <section class="card table-card">
    <div class="section-heading">
      <h3>
        指标清单 <span class="count">{{ filtered.length }}</span>
      </h3>
      <span class="hint">百分数直接以 % 表示，0.35 即 0.35%</span>
    </div>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>指标 / 编码</th>
            <th>监测章节</th>
            <th>本期值</th>
            <th>演示目标</th>
            <th>监测状态</th>
            <th>数据来源</th>
            <th>责任部门</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="item in visible"
            :key="item.id"
            class="clickable-row"
            @click="open(item)"
          >
            <td>
              <button class="table-link" @click.stop="open(item)">
                {{ item.name }}</button
              ><small class="cell-sub">{{ item.code }}</small>
            </td>
            <td>{{ item.chapter }}</td>
            <td class="numeric">{{ formatValue(item.value, item.unit) }}</td>
            <td>
              {{ item.direction === "high" ? "≥" : "≤" }}
              {{ formatValue(item.target, item.unit) }}
            </td>
            <td><Badge :value="item.light" /></td>
            <td>
              <span class="source-tag">{{ label(item.source) }}</span>
            </td>
            <td>{{ item.owner }}</td>
            <td><ChevronRight :size="16" /></td>
          </tr>
        </tbody>
      </table>
      <div v-if="!visible.length" class="empty">
        <Search :size="28" /><strong>没有符合条件的指标</strong>
        <p>调整搜索关键词或筛选条件后重试</p>
      </div>
    </div>
    <div class="pagination">
      <span>共 {{ filtered.length }} 项 · 每页 10 项</span>
      <div>
        <button
          class="button button-small"
          :disabled="page <= 1"
          @click="page--"
        >
          上一页</button
        ><span
          >{{ page }} / {{ Math.max(1, Math.ceil(filtered.length / 10)) }}</span
        ><button
          class="button button-small"
          :disabled="page * 10 >= filtered.length"
          @click="page++"
        >
          下一页
        </button>
      </div>
    </div>
  </section>
  <Modal v-if="selected" :title="selected.name" drawer @close="selectedId = ''">
    <div class="detail-heading">
      <span class="code-label">{{ selected.code }}</span
      ><Badge :value="selected.light" /><span class="hint"
        >定义版本 v{{ selected.definitionVersion }}</span
      >
    </div>
    <div class="detail-metrics">
      <div>
        <small>本期值</small
        ><strong>{{ formatValue(selected.value, selected.unit) }}</strong>
      </div>
      <div>
        <small>演示目标</small
        ><strong
          >{{ selected.direction === "high" ? "≥" : "≤" }}
          {{ formatValue(selected.target, selected.unit) }}</strong
        >
      </div>
      <div>
        <small>自动原值</small
        ><strong>{{ formatValue(selected.autoValue, selected.unit) }}</strong>
      </div>
    </div>
    <div class="tabs">
      <button
        :class="{ active: detailTab === 'trend' }"
        @click="detailTab = 'trend'"
      >
        <TrendingUp :size="15" />趋势与科室</button
      ><button
        :class="{ active: detailTab === 'definition' }"
        @click="detailTab = 'definition'"
      >
        <BookOpen :size="15" />指标定义</button
      ><button
        :class="{ active: detailTab === 'members' }"
        @click="detailTab = 'members'"
      >
        <Users :size="15" />脱敏成员
      </button>
    </div>
    <template v-if="detailTab === 'trend'"
      ><h3 class="spaced-heading">
        近 12 个月趋势 <small class="muted">{{ selected.unit }}</small>
      </h3>
      <Chart :option="trend" />
      <h3 class="spaced-heading">科室分布</h3>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>科室</th>
              <th>本期值</th>
              <th>分子 / 分母</th>
              <th>状态</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="dept in selected.departmentValues" :key="dept.code">
              <td>{{ dept.name }}</td>
              <td class="numeric">
                {{ formatValue(dept.value, selected.unit) }}
              </td>
              <td>
                {{ formatValue(dept.numerator) }} /
                {{ formatValue(dept.denominator) }}
              </td>
              <td><Badge :value="dept.light" /></td>
            </tr>
          </tbody>
        </table>
      </div>
      <h3 class="spaced-heading">年度计算依据</h3>
      <div class="annual-grid">
        <div v-for="year in selected.annualValues" :key="year.year">
          <small>{{ year.year }} 年</small
          ><strong>{{ formatValue(year.value, selected.unit) }}</strong>
        </div>
      </div></template
    >
    <template v-if="detailTab === 'definition'"
      ><dl class="definition-list">
        <dt>分子口径</dt>
        <dd>{{ selected.numeratorText }}</dd>
        <dt>分母口径</dt>
        <dd>{{ selected.denominatorText }}</dd>
        <dt>纳入规则</dt>
        <dd>{{ selected.includeText }}</dd>
        <dt>排除规则</dt>
        <dd>{{ selected.excludeText }}</dd>
        <dt>数据来源</dt>
        <dd>
          <code v-for="table in selected.sourceTables" :key="table">{{
            table
          }}</code>
        </dd>
        <dt>计算与汇总</dt>
        <dd>
          {{ label(selected.scoreType) }} · {{ label(selected.annualAgg) }} ·
          {{ selected.direction === "high" ? "越高越优" : "越低越优" }}
        </dd>
        <dt>采集信息</dt>
        <dd>
          {{ label(selected.collectMode) }} · 当前采用
          {{ label(selected.source) }} · 批次 {{ selected.batchId }}
        </dd>
        <dt>排除数量</dt>
        <dd>{{ selected.excludedCount }} 条</dd>
      </dl>
      <div class="info-strip">
        <Info :size="17" />人工审核通过后保留自动原值，便于追溯覆盖来源。
      </div></template
    >
    <template v-if="detailTab === 'members'"
      ><div v-if="!canMembers" class="empty">
        <Users :size="32" /><strong>当前角色不开放成员明细</strong>
        <p>
          院级领导与信息科可查看汇总；患者级合成明细由质控或本科负责人查看。
        </p>
      </div>
      <template v-else
        ><div class="info-strip">
          <Info
            :size="17"
          />所有住院号固定脱敏，均为合成样例。非患者指标可能没有成员清单。
        </div>
        <div class="inline-filters">
          <select
            v-model="department"
            aria-label="成员科室"
            :disabled="role === 'department'"
            @change="changeDept"
          >
            <option v-if="role !== 'department'" value="ALL">全部科室</option>
            <option
              v-for="dept in state!.departments"
              :key="dept.code"
              :value="dept.code"
            >
              {{ dept.name }}
            </option></select
          ><select
            v-model="memberRole"
            aria-label="成员类型"
            @change="memberPage = 1"
          >
            <option value="denominator">分母成员</option>
            <option value="numerator">分子成员</option>
          </select>
        </div>
        <div v-if="membersLoading" class="empty">
          <span class="spinner"></span>正在读取成员明细…
        </div>
        <div v-else-if="membersError" class="error-box">
          {{ membersError
          }}<button class="text-button" @click="loadMembers">重新加载</button>
        </div>
        <template v-else-if="members"
          ><div class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>脱敏住院号</th>
                  <th>科室 / 出院日期</th>
                  <th>性别 / 年龄</th>
                  <th>主诊断</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="(member, index) in members.items"
                  :key="`${member.inpatientNo}-${index}`"
                >
                  <td class="numeric">{{ member.inpatientNo }}</td>
                  <td>
                    {{ member.deptName
                    }}<small class="cell-sub">{{ member.dischargeDate }}</small>
                  </td>
                  <td>{{ member.sex }} / {{ member.age }}</td>
                  <td>{{ member.mainDiagnosis }}</td>
                </tr>
              </tbody>
            </table>
            <div v-if="!members.items.length" class="empty small">
              该范围暂无患者成员
            </div>
          </div>
          <div class="pagination">
            <span>共 {{ members.total }} 条 · 每页 20 条</span>
            <div>
              <button
                class="icon-button"
                aria-label="成员上一页"
                :disabled="memberPage <= 1"
                @click="memberPage--"
              >
                <ArrowLeft :size="16" /></button
              ><span
                >{{ memberPage }} /
                {{ Math.max(1, Math.ceil(members.total / 20)) }}</span
              ><button
                class="icon-button"
                aria-label="成员下一页"
                :disabled="memberPage * 20 >= members.total"
                @click="memberPage++"
              >
                <ArrowRight :size="16" />
              </button>
            </div></div></template></template
    ></template>
  </Modal>
</template>
