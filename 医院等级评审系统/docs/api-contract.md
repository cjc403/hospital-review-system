# 演示版接口契约 v1

只用于本地演示。服务 http://127.0.0.1:18080，Vite 18173 将 /api 代理到后端。JSON UTF-8，不包 data 外壳。错误 {message:string}，400/403/404/409。角色标头 X-Demo-Role: quality（默认）/leader/department/information。写入只允许 quality；information 可登记政策。成员明细 leader/information 禁止。department 只见 CARD 科室，不可改管理规则。演示角色可切换，不是认证。

## 读取

GET /api/health → {status:"ok",demo:true}

GET /api/workspace → Workspace，字段如下。所有 id 用 string；集合永远数组；数值为 JSON number 或 null；百分比用百分数值（0.35=0.35%），ratio/errorRate 为0到1。

```ts
type Light='green'|'yellow'|'red'|'na';
type Workspace={
 meta:{hospitalName:string,standard:string,period:string,demo:boolean,dataAsOf:string,totalMonitoringClauses:number},
 departments:{code:string,name:string,owner:string}[],
 indicators:Indicator[], batches:Batch[], manualValues:Manual[], reconciliations:Reconcile[],
 auditSamples:AuditSample[], prerequisites:Prereq[], improvements:Improvement[], policies:Policy[],
 scoreRuns:ScoreRun[], evidenceFiles:Evidence[], auditLogs:AuditLog[]
};
type Indicator={id:string,code:string,name:string,chapter:string,dimension:string,owner:string,unit:string,
 direction:'high'|'low',scoreType:'all_or_nothing'|'interval_trend',annualAgg:string,
 collectMode:'auto'|'manual',target:number,warn:number,value:number|null,autoValue:number|null,
 numerator:number|null,denominator:number|null,light:Light,source:'auto'|'manual',definitionVersion:number,
 numeratorText:string,denominatorText:string,includeText:string,excludeText:string,sourceTables:string[],
 batchId:string,excludedCount:number,
 trends:{period:string,value:number|null}[], annualValues:{year:number,value:number|null}[],
 departmentValues:{code:string,name:string,value:number|null,numerator:number|null,denominator:number|null,light:Light}[]};
type Batch={id:string,period:string,status:'success'|'failed',batchKind:'auto'|'manual',indicatorCount:number,createdAt:string,note:string};
type Manual={id:string,indicatorId:string,indicatorName:string,deptCode:string,period:string,value:number,note:string,
 status:'submitted'|'approved'|'rejected',submitter:string,reviewer:string,submittedAt:string};
type Reconcile={id:string,inpatientNo:string,dischargeDate:string,deptName:string,diffType:string,homeValue:string,hqmsValue:string,status:'pending'|'explained',note:string};
type AuditSample={id:string,period:string,sampleCount:number,errorCount:number,errorRate:number,createdAt:string,
 items:{indicatorId:string,name:string,homeValue:number|null,hqmsValue:number|null,deviation:number|null,result:string}[]};
type Prereq={id:string,code:string,section:string,title:string,compliance:'compliant'|'non_compliant'|'unknown',workflow:'draft'|'submitted'|'accepted'|'rejected',owner:string,statement:string,systemGenerated:boolean,evidenceCount:number};
type Improvement={id:string,indicatorId:string,indicatorName:string,origin:'red_light'|'worst_year',status:string,owner:string,dueDate:string,baseline:number,target:number,latestValue:number|null,actionText:string,closeMode:string,closeNote:string,evidenceCount:number};
type Policy={id:string,code:string,title:string,kind:string,status:string,publishedOn:string,effectiveOn:string,version:number,impacts:string[],note:string};
type ScoreRun={id:string,cycleStart:number,cycleEnd:number,yearCount:number,trial:boolean,conformCount:number,includedCount:number,ratio:number,grade:'jia'|'yi'|'bing'|'deferred'|null,createdAt:string,adopted:boolean,warnings:string[],
 items:{indicatorId:string,name:string,unit:string,yearValues:{year:number,value:number|null}[],mergedValue:number|null,conform:boolean|null,reasonCode:string}[]};
type Evidence={id:string,ownerType:string,ownerId:string,fileName:string,size:number,version:number,createdAt:string};
type AuditLog={id:string,actor:string,action:string,objectId:string,detail:string,createdAt:string};
```

GET /api/indicators/{id}/members?dept=ALL&role=denominator&page=1&size=20 → {items:[{inpatientNo,dischargeDate,deptCode,deptName,sex,age,mainDiagnosis,memberRole}],total:number,page:number,size:number}。住院号固定脱敏，床位/人员等非患者指标返回空清单。实际成员总数与所选演示事实分子/分母相符。

GET /api/evidence/{id}/download → 文件。
GET /api/reports/{scoreRunId} → DOCX 附件，报告数字来自该次保存的 ScoreRun 快照，不读取后续可变的指标/前置结论。

## 写入

写入成功返回该对象或 {id:string}；前端随后重新 GET workspace。禁止用前端临时状态冒充后端保存。

| 方法 | 路径 | 请求 |
| --- | --- | --- |
| POST | /api/manual-values | {indicatorId,deptCode:"ALL",period:"2026-09",value:number,note:string}，note必填 |
| POST | /api/manual-values/{id}/review | {decision:"approved"或"rejected",note:string} |
| PATCH | /api/reconciliations/{id} | {status:"explained"或"pending",note:string} |
| POST | /api/audit-samples | {period:"2026-09"}，依据预置可核查样例产生新结果，不修改事实 |
| PUT | /api/prerequisites/{id} | {compliance,workflow,statement:string}，非符合需说明和附件；系统条款拒绝人工修改 |
| POST | /api/score-runs | {cycleStart:2023,cycleEnd:2026}，范围1至4年 |
| POST | /api/score-runs/{id}/adopt | {} |
| POST | /api/improvements | {indicatorId,origin:"red_light",owner:string,dueDate:"2026-11-30"} |
| PATCH | /api/improvements/{id} | {status,owner,dueDate,actionText,closeMode,closeNote}，允许只传要改的字段 |
| POST | /api/policies | {code,title,kind,publishedOn,effectiveOn,impacts:string[],note} |
| POST | /api/policies/{id}/confirm | {}，涉及scoring_rule拒绝 |
| POST | /api/calc-batches | {period:"2026-09"}，产生新自动批次，保留人工覆盖 |
| POST | /api/evidence | multipart: file,ownerType(prereq/improvement),ownerId，最大5MiB，仅pdf/png/jpg/jpeg/txt/docx |
| POST | /api/demo/reset | {confirm:"RESET_DEMO"}，仅quality，恢复初始演示业务状态 |

所有业务修改及成员查看记入 auditLogs。非法状态转换400或409；重复审核409；不存在404。成员不可根据 UI 隐藏代替后端角色检查。附件文件名不得作为路径拼接；不允许 svg/html/js/exe。
