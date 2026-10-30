<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import {
  Activity,
  LayoutDashboard,
  ChartNoAxesCombined,
  ClipboardList,
  ClipboardCheck,
  ShieldCheck,
  Calculator,
  TrendingUp,
  FileText,
  Settings2,
  ChevronDown,
  Bell,
  RefreshCw,
  Search,
  Hospital,
  PanelLeftClose,
  PanelLeftOpen,
  FolderTree,
  X,
  CheckCircle2,
  AlertCircle,
  ChevronRight,
  CircleHelp,
} from "lucide-vue-next";
import {
  workspace,
  role,
  roleLabels,
  loading,
  busy,
  loadError,
  notice,
  loadWorkspace,
} from "./store";
import DashboardView from "./components/DashboardView.vue";
import IndicatorsView from "./components/IndicatorsView.vue";
import VerificationView from "./components/VerificationView.vue";
import ManualView from "./components/ManualView.vue";
import PrerequisitesView from "./components/PrerequisitesView.vue";
import ScoreView from "./components/ScoreView.vue";
import ImprovementsView from "./components/ImprovementsView.vue";
import PoliciesView from "./components/PoliciesView.vue";
import OperationsView from "./components/OperationsView.vue";
import ReviewWorkbench from "./v2/ReviewWorkbench.vue";
const pages = [
  {
    id: "dashboard",
    name: "评审总览",
    icon: LayoutDashboard,
    component: DashboardView,
    group: "工作台",
  },
  {
    id: "indicators",
    name: "指标监测",
    icon: ChartNoAxesCombined,
    component: IndicatorsView,
    group: "工作台",
  },
  {
    id: "verification",
    name: "数据核查",
    icon: ClipboardList,
    component: VerificationView,
    group: "工作台",
  },
  {
    id: "manual",
    name: "补录审核",
    icon: ClipboardCheck,
    component: ManualView,
    group: "工作台",
  },
  {
    id: "workbench",
    name: "评审作业台",
    icon: FolderTree,
    component: ReviewWorkbench,
    group: "v0.2 作业中心",
  },
  {
    id: "prerequisites",
    name: "前置自评",
    icon: ShieldCheck,
    component: PrerequisitesView,
    group: "评审与改进",
  },
  {
    id: "score",
    name: "模拟等次",
    icon: Calculator,
    component: ScoreView,
    group: "评审与改进",
  },
  {
    id: "improvements",
    name: "持续改进",
    icon: TrendingUp,
    component: ImprovementsView,
    group: "评审与改进",
  },
  {
    id: "policies",
    name: "政策文件",
    icon: FileText,
    component: PoliciesView,
    group: "系统管理",
  },
  {
    id: "operations",
    name: "运行与审计",
    icon: Settings2,
    component: OperationsView,
    group: "系统管理",
  },
];
const activePage = ref("dashboard"),
  sidebarOpen = ref(false);
const current = computed(
  () => pages.find((x) => x.id === activePage.value) || pages[0]!,
);
const todoCount = computed(
  () =>
    workspace.value?.manualValues.filter((x) => x.status === "submitted")
      .length || 0,
);
function navigate(page: string) {
  activePage.value = page;
  sidebarOpen.value = false;
  window.scrollTo({ top: 0, behavior: "smooth" });
}
watch(role, async () => {
  workspace.value = null;
  await loadWorkspace();
});
onMounted(loadWorkspace);
</script>
<template>
  <div class="app-shell">
    <div
      v-if="sidebarOpen"
      class="sidebar-scrim"
      @click="sidebarOpen = false"
    ></div>
    <aside class="sidebar" :class="{ open: sidebarOpen }">
      <a class="brand" href="#" @click.prevent="navigate('dashboard')"
        ><span class="brand-icon"
          ><Activity :size="26" :stroke-width="2.4"
        /></span>
        <div>
          <strong>医评智控</strong><small>HOSPITAL QUALITY REVIEW</small>
        </div></a
      >
      <div class="workspace-label">
        <span class="live-dot"></span>医院等级评审工作台<span>DEMO</span>
      </div>
      <nav aria-label="主导航">
        <template v-for="(page, index) in pages" :key="page.id"
          ><div
            v-if="index === 0 || pages[index - 1]?.group !== page.group"
            class="nav-group"
          >
            {{ page.group }}
          </div>
          <button
            class="nav-item"
            :class="{ active: activePage === page.id }"
            :aria-current="activePage === page.id ? 'page' : undefined"
            @click="navigate(page.id)"
          >
            <component :is="page.icon" :size="18" /><span>{{ page.name }}</span
            ><span v-if="page.id === 'manual' && todoCount" class="nav-count">{{
              todoCount
            }}</span
            ><ChevronRight
              v-else-if="activePage === page.id"
              :size="14"
            /></button
        ></template>
      </nav>
      <div class="sidebar-bottom">
        <div class="sidebar-demo">
          <ShieldCheck :size="18" />
          <div>
            <strong>本地演示 · 合成数据</strong
            ><small>为质量改进提供可追溯依据</small>
          </div>
        </div>
        <span class="sidebar-version">HQR WORKSPACE <i>v0.1 Demo</i></span>
      </div>
    </aside>
    <div class="main-shell">
      <header class="topbar">
        <div class="topbar-left">
          <button
            class="icon-button menu-toggle"
            aria-label="打开导航"
            @click="sidebarOpen = !sidebarOpen"
          >
            <PanelLeftOpen :size="21" />
          </button>
          <div class="breadcrumb">
            工作台<ChevronRight :size="13" /><strong>{{ current.name }}</strong>
          </div>
        </div>
        <div class="topbar-right">
          <span class="environment-pill"
            ><span class="live-dot"></span>演示环境 / 合成数据</span
          ><button
            class="icon-button"
            aria-label="刷新工作区"
            title="刷新工作区"
            :disabled="loading || busy"
            @click="loadWorkspace"
          >
            <RefreshCw :size="17" :class="{ spin: loading }" /></button
          ><button
            class="icon-button notification-button"
            aria-label="查看待审核补录"
            title="查看待审核补录"
            @click="navigate('manual')"
          >
            <Bell :size="18" /><span v-if="todoCount"></span>
          </button>
          <div class="topbar-divider"></div>
          <div class="role-select">
            <span class="avatar">{{
              role === "quality"
                ? "质"
                : role === "leader"
                  ? "院"
                  : role === "department"
                    ? "科"
                    : "信"
            }}</span>
            <div>
              <small>演示角色</small
              ><select
                v-model="role"
                :disabled="busy"
                aria-label="切换演示角色"
              >
                <option
                  v-for="(name, key) in roleLabels"
                  :key="key"
                  :value="key"
                >
                  {{ name }}
                </option>
              </select>
            </div>
            <ChevronDown :size="13" />
          </div>
        </div>
      </header>
      <main>
        <div class="workspace-title">
          <div>
            <div class="eyebrow">HOSPITAL QUALITY MANAGEMENT</div>
            <h1>
              {{ current.id === "dashboard" ? "评审工作总览" : current.name }}
            </h1>
            <p>
              {{ workspace?.meta.hospitalName || "江城市第一人民医院"
              }}<span class="title-divider">|</span
              >{{
                role === "department"
                  ? "本科室视角 · 心血管内科 CARD"
                  : "医院等级评审协同工作台"
              }}
            </p>
          </div>
          <div class="period-badge">
            <span class="calendar-symbol">月</span>
            <div>
              <small>当前数据周期</small
              ><strong>{{ workspace?.meta.period || "2026-09" }}</strong>
            </div>
          </div>
        </div>
        <div v-if="loading && !workspace" class="card initial-loader">
          <span class="spinner"></span>
          <h3>正在加载工作区</h3>
          <p>从本地服务读取最新演示数据…</p>
        </div>
        <div v-else-if="loadError && !workspace" class="card initial-loader">
          <AlertCircle :size="36" class="negative" />
          <h3>工作区暂时不可用</h3>
          <p>{{ loadError }}</p>
          <button class="button primary" @click="loadWorkspace">
            <RefreshCw :size="16" />重新连接
          </button>
        </div>
        <template v-else-if="workspace"
          ><div v-if="loadError" class="error-box">
            {{ loadError
            }}<button class="text-button" @click="loadWorkspace">
              重试刷新
            </button>
          </div>
          <div v-if="role !== 'quality'" class="role-notice">
            <ShieldCheck :size="15" />当前为{{ roleLabels[role] }}演示视角。{{
              role === "department"
                ? "仅展示本科 CARD 数据；管理操作由质控执行。"
                : role === "information"
                  ? "可登记政策元数据；成员明细不开放。"
                  : "查看全院汇总与试算，成员明细不开放。"
            }}
          </div>
          <component
            :is="current.component"
            :key="`${current.id}-${role}`"
            @navigate="navigate"
        /></template>
        <footer class="app-footer">
          <span>医评智控 · 医院等级评审演示系统</span
          ><span>本地运行 · 合成数据 · 等次仅供试算</span>
        </footer>
      </main>
    </div>
    <Transition name="toast"
      ><div
        v-if="notice"
        class="toast-message"
        :class="{ 'toast-error': notice.error }"
        role="status"
      >
        <AlertCircle v-if="notice.error" :size="19" /><CheckCircle2
          v-else
          :size="19"
        /><span>{{ notice.message }}</span
        ><button
          class="icon-button"
          aria-label="关闭提示"
          @click="notice = null"
        >
          <X :size="16" />
        </button></div
    ></Transition>
    <div v-if="busy" class="busy-indicator">
      <span class="spinner"></span>正在与本地服务同步…
    </div>
  </div>
</template>
