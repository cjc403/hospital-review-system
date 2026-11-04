<script setup lang="ts">
import { ref } from 'vue';
import Modal from './Modal.vue';
import { changePassword } from '../auth';
const emit=defineEmits<{close:[]}>();
const currentPassword=ref(''),newPassword=ref(''),confirmPassword=ref(''),busy=ref(false),error=ref('');
async function save(){if(newPassword.value!==confirmPassword.value){error.value='两次新密码不一致';return;}busy.value=true;error.value='';try{await changePassword(currentPassword.value,newPassword.value);emit('close');}catch(e){error.value=(e as Error).message;}finally{busy.value=false;}}
</script><template><Modal title="修改我的密码" @close="!busy&&emit('close')"><form @submit.prevent="save"><p class="hint">新密码须为 10 至 256 位。修改成功后会退出登录，请使用新密码重新登录。</p><label class="field">当前密码<input v-model="currentPassword" type="password" autocomplete="current-password" required :disabled="busy"/></label><label class="field">新密码<input v-model="newPassword" type="password" autocomplete="new-password" minlength="10" maxlength="256" required :disabled="busy"/></label><label class="field">再次输入新密码<input v-model="confirmPassword" type="password" autocomplete="new-password" minlength="10" maxlength="256" required :disabled="busy"/></label><div v-if="error" class="error-box" role="alert">{{error}}</div><div class="form-actions"><button class="button" type="button" :disabled="busy" @click="emit('close')">取消</button><button class="button primary" :disabled="busy">{{busy?'正在修改…':'保存并重新登录'}}</button></div></form></Modal></template>
