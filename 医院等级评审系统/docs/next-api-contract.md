# 接口契约 v2（v0.2 生产导向骨架）

> 与 `api-contract.md`（v1 演示契约）并存：v1 端点保持不变，作为回归基线；v2 为本版新增的生产形态接口。两者不共用领域状态。
>
> 数据与身份均为本地合成。v2 只在本机验证，未接真实医院、真实身份、真实对象存储。

## 1. 基础

- 前缀 `/api/v2`，服务 `http://127.0.0.1:18080`，经 Vite 代理到 18173。
- JSON UTF-8，不包 `data` 外壳。时间用 ISO-8601 字符串，业务日期用 `YYYY-MM-DD`。
- 错误体：`{code:string, message:string, requestId:string}`。错误码见第 8 节。
- 每个响应带 `X-Request-Id`；同一请求的所有审计与日志使用该 ID。

## 2. 身份与 profile

配置项 `review.profile`：`demo`（默认）或 `prod`。

| profile | 身份来源 | 拒绝行为 |
| --- | --- | --- |
| `demo` | `X-Demo-Role` 请求头映射演示账号 | 缺失或未知角色 → 401 |
| `prod` | `Authorization: Bearer <token>` 经 `TokenAuthenticator` 校验 | 出现 `X-Demo-Role` → 403 `profile_forbidden`；无有效令牌 → 401 |

演示角色映射：`quality→review_office`、`leader→leader`、`department→dept_head(CARD)`、`information→observer`。演示账号属于医院 `H001`。

认证上下文：`{userId, hospitalId, deptCode, roles[], profile, demo}`。

v2 角色：`review_office`（评审办）、`leader`（院领导）、`dept_head`（科室负责人）、`indicator_admin`（指标管理员）、`evidence_admin`（材料管理员）、`observer`（观察员）。

授权矩阵（服务端强制，前端隐藏菜单不构成授权）：

| 动作 | review_office | leader | dept_head | indicator_admin | evidence_admin | observer |
| --- | --- | --- | --- | --- | --- | --- |
| 读标准/条款 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 导入/发布/修订标准 | ✓ | | | | | |
| 分派条款任务 | ✓ | | | | | |
| 推进本人科室任务 | ✓ | | ✓（本科室） | | | |
| 审核/退回/关闭任务 | ✓ | | | | | |
| 上传材料 | ✓ | | ✓（本科室） | | ✓ | |
| 审核材料 | ✓ | | | | ✓ | |
| 导入数据批次 | ✓ | | | ✓ | | |
| 执行试算/导出报告 | ✓ | | | | | |
| 读跨科室汇总 | ✓ | ✓ | | ✓ | | ✓ |

数据范围：所有查询与写入按 `hospitalId` 过滤；`dept_head` 只可读写本人 `deptCode`。越界写入返回 403，越界读取同医院内不可见对象返回 404、跨医院一律 404。

## 3. 标准与条款

`GET /api/v2/me` → 认证上下文。

`GET /api/v2/departments` → `{items:[{code,name,hospitalName}]}`。按医院过滤；`dept_head` 只返回本科室。

`GET /api/v2/standards` → `{packages:[{code,title,issuer,versions:[{id,versionNo,status,publishedOn,effectiveFrom,checksum}]}]}`

`GET /api/v2/standards/versions/{versionId}` →
```ts
{
  version:{id,versionNo,status,publishedOn,effectiveFrom,appliesTo,checksum,createdAt},
  chapters:[{code,title,sort}], clauses:[{code,chapterCode,title,requirement,evidenceType,ownerScope,evaluationMethod,sourceLocator}],
  indicators:[{code,name,clauseCode,chapterCode,dimension,unit,direction,scoreType,annualAgg,collectMode,target,warn}],
  prerequisites:[{code,section,title,statement,systemGenerated}],
  counts:{chapters,clauses,indicators,prerequisites}
}
```

`POST /api/v2/standards/import`（review_office）
请求 `{pack:{...见第 7 节...}}`。校验失败 → 400 `validation_failed`，`details:[{path,rule,message}]`。成功 → 新 draft 版本。
校验规则：包编码/标题必填；章节编码唯一；条款父章节必须存在；指标/条款编码唯一；百分比指标必须有单位与方向；`direction∈{high,low}`；`scoreType∈{all_or_nothing,interval_trend}`；指标引用的 `clauseCode` 必须存在；前置编码唯一。

`POST /api/v2/standards/versions/{id}/publish`（review_office）→ 状态 `published`。已发布再发布 → 409 `immutable`。
`POST /api/v2/standards/versions/{id}/revision`（review_office）→ 生成 `versionNo+1` 的 draft 副本。已发布版本内容不可修改，只能修订新版本。

## 4. 任务

`GET /api/v2/tasks?dept=&status=&overdue=` → `{items:[Task], summary:{total,todo,inProgress,submitted,returned,accepted,overdue}}`
```ts
type Task={id,hospitalId,deptCode,versionId,clauseCode,title,assigneePost,priority,dueDate,status,returnReason,overdue,createdBy,createdAt,updatedAt}
```
`status`：`todo|in_progress|submitted|returned|accepted|cancelled`。`overdue` 为计算字段：未到终态且 `dueDate` 早于今天。

`POST /api/v2/tasks/assign`（review_office）
`{versionId, clauseCodes:[...], deptCode, assigneePost, priority, dueDate, requestKey}`
- `requestKey` 必填，按 `(hospitalId, requestKey)` 幂等：重复请求返回第一次的结果，不产生新任务。
- 科室必须在当前医院存在；版本必须是 `published`；条款必须存在于该版本。

`PATCH /api/v2/tasks/{id}`（见矩阵）
`{status, comment}`
状态机：`todo→in_progress`、`returned→in_progress`、`in_progress→submitted`、`submitted→accepted|returned`、`todo|in_progress→cancelled`。
- 非法流转 → 409 `conflict`；`accepted`/`cancelled` 为终态，再改 → 409 `immutable`。
- `returned` 必须带 `comment`，写入 `returnReason`。
- 每次流转写审计（actor、对象、前后状态、requestId）。

## 5. 证据资料库

`POST /api/v2/evidence`（multipart；review_office/dept_head/evidence_admin）
字段：`file`、`ownerType∈{clause,task,indicator,prereq,improvement}`、`ownerId`、`title`、`materialType`、`validUntil?`
- 单文件 ≤5MiB；扩展名仅 `pdf/png/jpg/jpeg/txt/docx`；按魔数校验内容与扩展名一致；`svg/html/js/exe` 一律拒绝。
- 服务端计算 SHA-256；同一材料同一 SHA-256 重复上传 → 返回既有版本（200，不新增版本）。
- 不同内容 → `versionNo+1`，旧版本只读保留。
- 响应 `{item:{id,title,status,ownerType,ownerId,createdAt},version:{id,versionNo,sha256,size,fileName,uploadedAt}}`。

`GET /api/v2/evidence?ownerType=&ownerId=` → `{items:[{...item,versionCount,latestVersion,status}]}`
`GET /api/v2/evidence/{itemId}` → `{item,versions:[...]}`（旧版本可查）
`POST /api/v2/evidence/{itemId}/review` `{decision:"approved"|"rejected", note}`（review_office/evidence_admin）→ 已审核再审核 → 409。
`GET /api/v2/evidence/versions/{versionId}/download` → 文件流；`Content-Disposition` 用原文件名；响应头不出现服务器路径。跨医院 404。

## 6. 数据质量、试算与报告

`GET /api/v2/data-sources` → `[{id,code,name,kind,active}]`（`kind∈synthetic|csv|json|manual`）

`POST /api/v2/batches`（review_office/indicator_admin）
`{sourceCode, period:"YYYY-MM", rows:[{indicatorCode,numerator,denominator,excludedCount,value,unit}]}`
- 质量规则：未知指标编码、同批重复指标、分母≤0（比例指标）、分子>分母、百分比值超出 0–100、周期不在 2025-01 至 2027-12 → `error`。
- 与上一已应用批次相比偏离 >50% → `warning`（不阻断）。
- 有 `error` → 批次 `rejected`，响应 200 但 `status:"rejected"` 且带 `issues`；该批次不可 apply、不可进入试算。
- 无 `error` → `validated`。

`POST /api/v2/batches/{id}/apply` → `applied`；`rejected` → 409 `quality_blocked`；重复 apply → 409 `immutable`。
`POST /api/v2/batches/{id}/replay` → 以同源同周期生成新批次（`replayOf` 指向原批次），重新走质量校验。
`GET /api/v2/batches?limit=30` → `{items:[{id,sourceCode,period,status,recordCount,rejectReason,replayOf,createdAt,appliedAt}]}`，按创建时间倒序，仅本院。
`GET /api/v2/batches/{id}` → `{batch, facts[], issues[], trace}`。

`POST /api/v2/score-runs`（review_office）
`{versionId, cycleStart, cycleEnd}`（1–4 年，2023–2026）
- 只使用 `applied` 批次的事实；按 `annualAgg` 聚合到年度；无可用事实 → 409 `conflict`。
- 结果保存不可变快照（含使用的批次 ID、指标口径版本、证据版本清单）；无更新/删除端点。
`GET /api/v2/score-runs/{id}` → `{run, items[]}`
`GET /api/v2/score-runs?limit=20` → `{items:[{id,versionId,versionNo,cycleStart,cycleEnd,grade,conformCount,includedCount,ratio,deferredReason,createdAt}]}`，仅本院，按创建时间倒序。
`GET /api/v2/score-runs/{id}/report` → DOCX，引用该快照的批次与材料版本。
`GET /api/v2/score-runs/{id}/compare/{otherId}` → `{added[],recovered[],worsened[],changedRule[]}`

## 7. 演示标准包结构

`resources/standards/demo-standard-v1.json`：
```json
{"package":{"code","title","issuer","sourceLocator"},
 "version":{"versionNo","publishedOn","effectiveFrom","appliesTo","note"},
 "chapters":[{"code","title","sort"}],
 "clauses":[{"code","chapterCode","title","requirement","evidenceType","ownerScope","evaluationMethod","sourceLocator"}],
 "indicators":[{"code","name","clauseCode","chapterCode","dimension","unit","direction","scoreType","annualAgg","collectMode","target","warn","numeratorRule","denominatorRule","includeRule","excludeRule","demoCurrent"}],
 "prerequisites":[{"code","section","title","statement","systemGenerated"}]}
```
`demoCurrent` 只被合成数据适配器使用，核心计分不读取。

## 8. 错误码

| HTTP | code | 含义 |
| --- | --- | --- |
| 400 | `validation_failed` | 字段或业务校验失败，带 `details` |
| 401 | `unauthenticated` | 未认证或角色无效 |
| 403 | `forbidden` | 角色或范围不允许 |
| 403 | `profile_forbidden` | 生产 profile 拒绝演示身份 |
| 404 | `not_found` | 对象不存在或不在可见范围 |
| 409 | `conflict` | 非法状态流转、重复审核 |
| 409 | `immutable` | 已发布/已应用/终态对象不可变更 |
| 409 | `quality_blocked` | 质量不合格批次被阻断 |
| 500 | `internal_error` | 未预期错误，消息不含内部路径 |

## 9. 不变性与审计

- 标准 `published` 版本、`applied` 批次、`score_run` 快照、`evidence_version`、`audit_event` 均无修改/删除端点，更正通过新版本、新批次或冲正记录。
- 审计事件：`{id,hospitalId,actor,action,objectType,objectId,detail,requestId,createdAt}`；所有写接口与成员/材料下载入审计。
- 敏感字段只写脱敏摘要；日志不含文件路径、令牌、患者标识。
