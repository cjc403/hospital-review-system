<script setup lang="ts">
import { computed, ref } from "vue";
import {
  RefreshCw,
  Database,
  RotateCcw,
  Info,
  Search,
  TerminalSquare,
  ShieldCheck,
} from "lucide-vue-next";
import { workspace as state, busy, canWrite, mutate } from "../store";
import { formatDate, label } from "../domain";
import Badge from "./Badge.vue";
import Modal from "./Modal.vue";
const tab = ref("batches"),
  rerunning = ref(false),
  resetting = ref(false),
  resetText = ref(""),
  period = ref("2026-09");
const search = ref(""),
  auditPage = ref(1);
const logs = computed(() =>
  [...state.value!.auditLogs]
    .reverse()
    .filter((x) =>
      `${x.action} ${x.actor} ${x.objectId} ${x.detail}`
        .toLowerCase()
        .includes(search.value.trim().toLowerCase()),
    ),
);
const logRows = computed(() =>
  logs.value.slice((auditPage.value - 1) * 20, auditPage.value * 20),
);
async function rerun() {
  if (
    await mutate(
      "/calc-batches",
      { period: period.value },
      "POST",
      "月结重跑已完成，历史批次和人工覆盖保留",
    )
  )
    rerunning.value = false;
}
async function reset() {
  if (
    await mutate(
      "/demo/reset",
      { confirm: resetText.value },
      "POST",
      "业务数据已恢复初始状态",
    )
  ) {
    resetting.value = false;
    resetText.value = "";
  }
}
</script>
<template>
  <div class="page-intro">
    <div>
      <h2>运行与审计</h2>
      <p>查看数据批次、业务操作轨迹，管理本地状态。</p>
    </div>
    <button
      v-if="canWrite"
      class="button primary"
      :disabled="busy"
      @click="
        rerunning = true;
        period = state!.meta.period;
      "
    >
      <RefreshCw :size="16" />执行月结重跑
    </button>
  </div>
  <div class="environment-grid">
    <div class="card environment-card">
      <span class="metric-icon teal"><ShieldCheck :size="22" /></span>
      <div>
        <h3>本地环境</h3>
        <p>数据管理 · 账号权限 · 操作审计</p>
      </div>
      <span class="badge badge-green">运行中</span>
    </div>
    <div class="card environment-card">
      <span class="metric-icon blue"><Database :size="22" /></span>
      <div>
        <h3>业务持久化</h3>
        <p>
          {{ state!.batches.length }} 个批次 ·
          {{ state!.auditLogs.length }} 条审计记录
        </p>
      </div>
    </div>
  </div>
  <div class="tabs page-tabs">
    <button :class="{ active: tab === 'batches' }" @click="tab = 'batches'">
      计算批次 <span class="count">{{ state!.batches.length }}</span></button
    ><button :class="{ active: tab === 'audit' }" @click="tab = 'audit'">
      操作审计 <span class="count">{{ state!.auditLogs.length }}</span></button
    ><button :class="{ active: tab === 'settings' }" @click="tab = 'settings'">
      维护
    </button>
  </div>
  <section v-if="tab === 'batches'" class="card table-card">
    <div class="section-heading">
      <h3>计算批次记录</h3>
      <span class="hint">新批次追加保存，不覆盖旧批次</span>
    </div>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>批次编号</th>
            <th>周期</th>
            <th>批次类型</th>
            <th>指标数</th>
            <th>状态</th>
            <th>创建时间</th>
            <th>运行说明</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="batch in [...state!.batches].reverse()" :key="batch.id">
            <td class="code-label">{{ batch.id }}</td>
            <td>{{ batch.period }}</td>
            <td>
              {{ batch.batchKind === "auto" ? "自动计算" : "人工审核批次" }}
            </td>
            <td class="numeric">{{ batch.indicatorCount }}</td>
            <td><Badge :value="batch.status" /></td>
            <td>{{ formatDate(batch.createdAt) }}</td>
            <td>{{ batch.note }}</td>
          </tr>
        </tbody>
      </table>
      <div v-if="!state!.batches.length" class="empty">暂无计算批次</div>
    </div>
  </section>
  <section v-else-if="tab === 'audit'" class="card table-card">
    <div class="section-heading">
      <h3>操作审计</h3>
      <div class="search-box compact">
        <Search :size="15" /><input
          v-model="search"
          aria-label="搜索审计记录"
          placeholder="搜索操作、对象或角色"
          @input="auditPage = 1"
        />
      </div>
    </div>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>操作时间</th>
            <th>操作者</th>
            <th>操作类型</th>
            <th>业务对象</th>
            <th>操作详情</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="log in logRows" :key="log.id">
            <td class="nowrap">{{ formatDate(log.createdAt) }}</td>
            <td>{{ log.actor }}</td>
            <td>
              <span class="source-tag">{{ log.action }}</span>
            </td>
            <td class="code-label">{{ log.objectId }}</td>
            <td class="wide-cell">{{ log.detail }}</td>
          </tr>
        </tbody>
      </table>
      <div v-if="!logRows.length" class="empty">暂无匹配的审计记录</div>
    </div>
    <div class="pagination">
      <span>共 {{ logs.length }} 条 · 每页 20 条</span>
      <div>
        <button
          class="button button-small"
          :disabled="auditPage <= 1"
          @click="auditPage--"
        >
          上一页</button
        ><span>{{ auditPage }}</span
        ><button
          class="button button-small"
          :disabled="auditPage * 20 >= logs.length"
          @click="auditPage++"
        >
          下一页
        </button>
      </div>
    </div>
  </section>
  <section v-else class="card maintenance-card">
    <div class="info-strip">
      <Info
        :size="17"
      />账号权限与操作日志由系统统一管理。
    </div>
    <div class="maintenance-row">
      <div>
        <h3>恢复初始数据</h3>
        <p>
          清除当前业务修改，恢复预置指标、条款与样例任务。仅质控管理员可执行。
        </p>
      </div>
      <button
        v-if="canWrite"
        class="button danger"
        :disabled="busy"
        @click="
          resetting = true;
          resetText = '';
        "
      >
        <RotateCcw :size="16" />重置数据</button
      ><span v-else class="hint">当前角色无维护权限</span>
    </div>
    <div class="maintenance-row">
      <div>
        <h3>数据与服务范围</h3>
        <p>
          前端 127.0.0.1:18173 · API 127.0.0.1:18080<br />默认周期
          2026-09，2026-10 可用于后续改进效果验证。
        </p>
      </div>
      <TerminalSquare :size="24" class="muted" />
    </div>
  </section>
  <Modal v-if="rerunning" title="执行月结重跑" @close="rerunning = false"
    ><form @submit.prevent="rerun">
      <div class="info-strip">
        <Info :size="17" />生成新的自动计算批次，保留旧批次及已批准人工覆盖。
      </div>
      <label class="field"
        >计算周期<input v-model="period" type="month" required
      /></label>
      <p class="hint">
        周期建议使用 2026-09 或 2026-10；后续周期用于验证持续改进效果。
      </p>
      <div class="form-actions">
        <button type="button" class="button" @click="rerunning = false">
          取消</button
        ><button class="button primary" :disabled="busy">
          {{ busy ? "计算中…" : "确认执行重跑" }}
        </button>
      </div>
    </form></Modal
  >
  <Modal v-if="resetting" title="确认重置数据" @close="resetting = false"
    ><form @submit.prevent="reset">
      <div class="warning-strip">
        <Info :size="20" />
        <p>
          此操作会恢复初始业务状态，当前补录、审核、抽查、试算和改进修改将被清除。
        </p>
      </div>
      <label class="field"
        >输入 RESET_DEMO 确认重置<input
          v-model="resetText"
          autocomplete="off"
          placeholder="RESET_DEMO"
          required
      /></label>
      <div class="form-actions">
        <button type="button" class="button" @click="resetting = false">
          取消</button
        ><button
          class="button danger"
          :disabled="busy || resetText !== 'RESET_DEMO'"
        >
          {{ busy ? "重置中…" : "确认恢复初始数据" }}
        </button>
      </div>
    </form></Modal
  >
</template>
