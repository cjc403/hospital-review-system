<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { authRequest } from '../auth';
const items=ref<Record<string,unknown>[]>([]),loading=ref(false),error=ref(''),search=ref('');
const rows=computed(()=>items.value.filter(i=>JSON.stringify(i).includes(search.value)));
async function load(){loading.value=true;error.value='';try{items.value=(await authRequest<{items:Record<string,unknown>[]}>('/audit?limit=100')).items;}catch(e){error.value=(e as Error).message;}finally{loading.value=false;}}
onMounted(load);
</script><template><section class="card table-card"><div class="section-heading"><div><h2>本院审计记录</h2><p class="hint">记录身份与业务操作，不展示口令或令牌。</p></div><div class="inline-filters"><input v-model="search" aria-label="搜索审计" placeholder="搜索操作、对象或人员"/><button class="button" :disabled="loading" @click="load">刷新</button></div></div><div v-if="error" class="error-box">{{error}}</div><div class="table-wrap"><table><thead><tr><th>时间</th><th>操作者</th><th>操作</th><th>对象</th><th>详情</th></tr></thead><tbody><tr v-for="(i,index) in rows" :key="String(i.id||index)"><td>{{i.createdAt||i.created_at}}</td><td>{{i.actor||i.actorName||i.username}}</td><td>{{i.action}}</td><td>{{i.objectId||i.object_id||i.targetId}}</td><td>{{i.detail}}</td></tr><tr v-if="!rows.length"><td colspan="5" class="empty">{{loading?'读取审计记录…':'暂无匹配的审计记录'}}</td></tr></tbody></table></div></section></template>
