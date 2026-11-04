import { present, displayText } from "./presentation";
import { ref } from 'vue';
import type { Role } from './types';

export interface User {
  id: string; username: string; displayName: string; roleCode: string; roleName: string;
  hospitalId: string; hospitalName: string; deptCode: string; deptName: string;
  permissions: string[]; legacyRole: Role; clauseCodes: string[]; status: string;
}
export type DemoAccount = Pick<User,'username'|'displayName'|'roleCode'|'roleName'|'hospitalId'|'hospitalName'|'deptCode'|'deptName'>;
export const currentUser = ref<User|null>(null);
export const sessionToken = ref('');
export const restoring = ref(true);
const clearListeners = new Set<()=>void>();
let sessionGeneration = 0;
export function onSessionCleared(callback:()=>void) { clearListeners.add(callback); }
export function hasPermission(permission:string) { return currentUser.value?.permissions.includes(permission) ?? false; }
export function hasAnyPermission(...permissions:string[]) { return permissions.some(hasPermission); }
export function clearSession() {
  sessionGeneration++;
  sessionToken.value = ''; currentUser.value = null;
  globalThis.sessionStorage?.removeItem('hqr.session');
  clearListeners.forEach(callback=>callback());
}
export function acceptSession(session:{token:string;user:User}) {
  clearSession(); sessionToken.value=session.token; currentUser.value=session.user;
  globalThis.sessionStorage?.setItem('hqr.session',session.token);
}
export function authHeaders(init?:HeadersInit) {
  const headers=new Headers(init); headers.delete('X-Demo-Role');
  if(sessionToken.value) headers.set('Authorization',`Bearer ${sessionToken.value}`);
  return headers;
}
export function rejectUnauthorized(status:number, credential=sessionToken.value) { if(status===401 && credential===sessionToken.value) clearSession(); }
export async function authRequest<T>(path:string,init:RequestInit={}):Promise<T> {
  const credential=sessionToken.value;
  const headers=authHeaders(init.headers);
  if(init.body) headers.set('Content-Type','application/json');
  let response:Response;
  try { response=await fetch(`/api/auth${path}`,{...init,headers}); }
  catch { throw new Error('无法连接本地服务，请确认系统已启动。'); }
  const body=await response.json().catch(()=>({}));
  if(!response.ok) { rejectUnauthorized(response.status,credential); throw new Error(displayText(body.message||`请求失败（${response.status}）`)); }
  return present(body) as T;
}
export async function login(username:string,password:string) {
  const session=await authRequest<{token:string;user:User}>('/login',{method:'POST',body:JSON.stringify({username,password})});
  acceptSession(session);
}
export async function restoreSession() {
  restoring.value=true;
  const generation=sessionGeneration;
  sessionToken.value=globalThis.sessionStorage?.getItem('hqr.session')||'';
  try { if(sessionToken.value) { const user=await authRequest<User>('/me'); if(generation===sessionGeneration) currentUser.value=user; } }
  catch { clearSession(); }
  finally { restoring.value=false; }
}
export async function logout() {
  const pending = authRequest('/logout',{method:'POST'});
  clearSession();
  await pending;
}

export async function changePassword(currentPassword:string,newPassword:string) {
  await authRequest('/password',{method:'POST',body:JSON.stringify({currentPassword,newPassword})});
  clearSession();
}
