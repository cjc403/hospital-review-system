<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { Activity, ArrowRight, ShieldCheck, Building2, LockKeyhole } from 'lucide-vue-next';
import { authRequest, login, type DemoAccount } from '../auth';
const username=ref(''),password=ref(''),busy=ref(false),error=ref(''),directoryError=ref('');
const accounts=ref<DemoAccount[]>([]),filter=ref('');
const visible=computed(()=>accounts.value.filter(a=>`${a.displayName} ${a.username} ${a.roleName} ${a.deptName||''} ${a.hospitalName}`.includes(filter.value)));
async function submit(){busy.value=true;error.value='';try{await login(username.value.trim(),password.value);password.value='';}catch(e){error.value=(e as Error).message;}finally{busy.value=false;}}
onMounted(async()=>{try{accounts.value=(await authRequest<{items:DemoAccount[]}>('/demo-accounts')).items;}catch(e){directoryError.value=(e as Error).message;}});
</script>
<template>
 <div class="login-page">
  <section class="login-story"><a class="login-brand"><Activity :size="32"/><span>医评智控<small>HOSPITAL QUALITY REVIEW</small></span></a>
   <div class="login-story-body"><span class="login-kicker">连接标准 · 协同评审 · 持续改进</span><h1>让质量管理<br/>成为每一天的行动。</h1><p>从条款任务到证据审核，从指标监测到整改闭环。<br/>在同一个工作台，推动每一项改进落地。</p>
   <div class="login-journey"><span>01 标准与任务</span><span>02 数据与证据</span><span>03 评审与改进</span></div>
   <div class="login-chart" aria-hidden="true"><i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i><div>QUALITY / CONTINUOUS IMPROVEMENT</div></div></div>
   <div class="login-foot"><ShieldCheck :size="16"/>标准有依据 · 过程可追溯 · 改进有闭环</div>
  </section>
  <section class="login-panel"><div class="login-form-wrap"><span class="login-welcome">欢迎回来</span><h2>登录医院评审工作台</h2><p class="muted">使用分配的账号进入您的工作空间</p>
   <form class="login-form" @submit.prevent="submit"><label class="field">账号<input v-model="username" autocomplete="username" required autofocus placeholder="请输入账号" :disabled="busy"/></label><label class="field">密码<input v-model="password" type="password" autocomplete="current-password" required placeholder="请输入密码" :disabled="busy"/></label>
   <div v-if="error" class="error-box" role="alert">{{error}}</div><button class="button primary login-submit" :disabled="busy">{{busy?'正在验证身份…':'登录工作台'}}<ArrowRight :size="18"/></button></form>
   <p class="login-security"><LockKeyhole :size="14"/>身份与访问范围由服务端验证 · 会话保存在当前标签页</p>
   <details class="demo-directory"><summary><Building2 :size="16"/>账号目录 <span>{{accounts.length}} 个账号</span></summary><p class="hint">选择账号可填入用户名；请输入统一初始密码。角色权限由登录后的服务器身份确定。</p><input v-model="filter" placeholder="搜索姓名、角色、医院或科室" aria-label="筛选账号"/><p v-if="directoryError" class="negative">{{directoryError}}</p><div class="demo-account-list"><button v-for="a in visible" :key="a.username" type="button" @click="username=a.username"><strong>{{a.displayName}}<span>{{a.roleName}}</span></strong><small>{{a.username}} · {{a.deptName||a.hospitalName}}</small></button></div></details>
   <p class="login-legal">医院等级评审协同系统<br/>质量监测 · 评审协同 · 持续改进</p>
  </div></section>
 </div>
</template>
