import { hasAnyPermission, hasPermission } from './auth';
import type { V2TaskStatus } from './v2/types';
const pagePermissions: Record<string,string[]> = {
  dashboard:['metric.read'], indicators:['metric.read'], verification:['legacy.write'],
  manual:['manual.submit','legacy.write'], workbench:['standard.read','task.read','evidence.read','batch.read','assessment.read'],
  v3:['metric.read','batch.read','alert.read','improvement.read','report.read'],
  prerequisites:['assessment.read'], score:['assessment.read'], improvements:['legacy.write'],
  policies:['legacy.write'], maintenance:['legacy.write'], operations:['audit.read'], accounts:['account.manage'],
};
export function canOpenPage(id:string) { return id==='home' || hasAnyPermission(...(pagePermissions[id]||[])); }
export function permittedTaskActions(status:V2TaskStatus):V2TaskStatus[] {
  const result:V2TaskStatus[]=[];
  if(hasPermission('task.progress')) {
    if(status==='todo'||status==='returned') result.push('in_progress');
    if(status==='in_progress') result.push('submitted');
  }
  if(hasPermission('task.review') && status==='submitted') result.push('accepted','returned');
  if(hasPermission('task.assign') && ['todo','in_progress'].includes(status)) result.push('cancelled');
  return result;
}
