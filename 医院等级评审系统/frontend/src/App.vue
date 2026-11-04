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
  Bell,
  RefreshCw,
  Hospital,
  PanelLeftOpen,
  FolderTree,
  Network,
  X,
  CheckCircle2,
  AlertCircle,
  ChevronRight,
} from "lucide-vue-next";
import {
  workspace,
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
import OperationsView from "./components/AuthAuditView.vue";
import MaintenanceView from "./components/OperationsView.vue";
import LoginView from "./components/LoginView.vue";
import MyWorkbench from "./components/MyWorkbench.vue";
import AccountsView from "./components/AccountsView.vue";
import PasswordDialog from "./components/PasswordDialog.vue";
import { currentUser, restoring, restoreSession, logout, onSessionCleared } from "./auth";
import { canOpenPage } from "./access";
import ReviewWorkbench from "./v2/ReviewWorkbench.vue";
import V3Workbench from "./v3/V3Workbench.vue";
const allPages = [
  { id: "home", name: "我的工作台", icon: Hospital, component: MyWorkbench, group: "我的工作" },
  { id: "accounts", name: "账号管理", icon: Settings2, component: AccountsView, group: "账号与权限" },
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
    id: "v3",
    name: "v3 数据中台",
    icon: Network,
    component: V3Workbench,
    group: "v3 数据中台",
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
    id: "maintenance",
    name: "运行维护",
    icon: Settings2,
    component: MaintenanceView,
    group: "系统管理",
  },
  {
    id: "operations",
    name: "审计记录",
    icon: Settings2,
    component: OperationsView,
    group: "系统管理",
  },
];
const pages = computed(() => allPages.filter(page => canOpenPage(page.id)));
const passwordOpen = ref(false);
const refreshKey = ref(0);
const activePage = ref("home"),
  sidebarOpen = ref(false);
const current = computed(
  () => pages.value.find((x) => x.id === activePage.value) || pages.value[0]!,
);
const todoCount = computed(
  () =>
    workspace.value?.manualValues.filter((x) => x.status === "submitted")
      .length || 0,
);
function navigate(page: string) {
  if (!canOpenPage(page)) return;
  activePage.value = page;
  sidebarOpen.value = false;
  window.scrollTo({ top: 0, behavior: "smooth" });
}
const independent = computed(() => ['home','accounts','operations','workbench','v3'].includes(current.value.id));
watch([activePage, currentUser], async () => {
  if (currentUser.value && !independent.value && !workspace.value) await loadWorkspace();
});
onSessionCleared(() => { activePage.value='home'; passwordOpen.value=false; });
async function refreshCurrent() { if (independent.value) refreshKey.value++; else await loadWorkspace(); }
async function signOut() { try { await logout(); } catch { /* local session is cleared even if offline */ } }
onMounted(restoreSession);
</script>
<template>
  <div v-if="restoring" class="auth-restoring"><span class="spinner"></span>正在恢复登录会话…</div>
  <LoginView v-else-if="!currentUser" />
  <div v-else class="app-shell">
    <div
      v-if="sidebarOpen"
      class="sidebar-scrim"
      @click="sidebarOpen = false"
    ></div>
    <aside class="sidebar" :class="{ open: sidebarOpen }">
      <a class="brand" href="#" @click.prevent="navigate('home')"
        ><span class="brand-icon"
          ><Activity :size="26" :stroke-width="2.4"
        /></span>
        <div>
          <strong>医评智控</strong><small>HOSPITAL QUALITY REVIEW</small>
        </div></a
      >
      <div class="workspace-label">
        <span class="live-dot"></span>医院等级评审工作台
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
        <span class="sidebar-version">HQR WORKSPACE <i>v3</i></span>
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
          <button
            class="icon-button"
            aria-label="刷新工作区"
            title="刷新工作区"
            :disabled="loading || busy"
            @click="refreshCurrent"
          >
            <RefreshCw :size="17" :class="{ spin: loading }" /></button
          ><button
            class="icon-button notification-button"
            aria-label="查看我的工作台"
            title="查看我的工作台"
            @click="navigate('home')"
          >
            <Bell :size="18" /><span v-if="todoCount"></span>
          </button>
          <div class="topbar-divider"></div>
          <div class="role-select"><span class="avatar">{{currentUser.displayName.slice(0,1)}}</span><div><small>{{currentUser.roleName}}</small><strong>{{currentUser.displayName}}</strong></div></div>
          <button class="text-button" @click="passwordOpen=true">修改密码</button>
          <button class="text-button" @click="signOut">退出</button>
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
              {{ currentUser.hospitalName
              }}<span class="title-divider">|</span
              >{{
                currentUser.deptName ? `授权科室 · ${currentUser.deptName} ${currentUser.deptCode}` : "医院等级评审协同工作台"
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
        <component v-if="independent" :is="current.component" :key="`${current.id}-${currentUser.id}-${refreshKey}`" @navigate="navigate"/>
        <div v-else-if="loading && !workspace" class="card initial-loader">
          <span class="spinner"></span>
          <h3>正在加载工作区</h3>
          <p>从本地服务读取最新数据…</p>
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
          <div class="role-notice"><ShieldCheck :size="15"/>{{currentUser.roleName}} · {{currentUser.deptName || '本院范围'}} · 操作与数据范围以当前账号授权为准</div>
          <component
            :is="current.component"
            :key="`${current.id}-${currentUser.id}-${refreshKey}`"
            @navigate="navigate"
        /></template>
        <footer class="app-footer">
          <span>医评智控 · 医院等级评审系统</span
          ><span>质量监测 · 协同评审 · 持续改进</span>
        </footer>
      </main>
    </div>
    <PasswordDialog v-if="passwordOpen" @close="passwordOpen=false"/>
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
