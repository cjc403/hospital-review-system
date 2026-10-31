# v3 接口契约：数据中台与指标引擎

> 前缀 `/api/v3`。v1、v2 端点与响应形状保持不变；v3 只做新增。
> 当前只使用虚构医院与合成数据，本地模拟服务验证。认证、租户与科室范围复用 v2（`AuthFilter`）。
> 验证状态（2026-10-31）：后端 78 项测试、前端 36 项、集成检查 24 组（含 v3 七组）、真实 PostgreSQL 迁移到版本 4 与备份恢复均通过。**真实接入未验证。**

## 1. 基础

- 错误体沿用 v2：`{code,message,requestId,details}`；`X-Request-Id` 贯穿审计。
- 新增错误码：`formula_not_allowed`(400)、`mapping_incomplete`(400)、`invalid_transition`(409)、`quality_blocked`(409)、`immutable`(409)。
- 角色沿用：`review_office` / `leader` / `dept_head` / `indicator_admin` / `evidence_admin` / `observer`。
- 写操作：指标定义、映射、批次导入/应用、预警处置、整改、报告 → `review_office`；批次导入同时允许 `indicator_admin`。
- 范围：医院为租户边界；`dept_head` 只能读本科室结果、预警与整改；`leader`/`observer` 只读汇总，不返回患者级字段。

## 2. 指标定义与受控公式

`formulaType` 只允许：`ratio`、`weighted_ratio`、`month_avg`、`year_end_snapshot`、`drg_formula`、`trend`。任何其他取值 → 400 `formula_not_allowed`。不接受任意 SQL 或脚本。

`GET /api/v3/metric-definitions?versionId=` → `{versionId, items:[{id,code,name,unit,direction,ownerScope,formulaType,aggregation,target,warning,versionNo,status,effectiveFrom}]}`

`POST /api/v3/metric-definitions`（review_office）
`{versionId, code, name, unit, direction:"high"|"low", ownerScope, formulaType, aggregation, target, warning}` → 新版本行（`versionNo` 自增，`status=published`）。同一 `code` 再次提交即生成新版本；**不提供修改或删除端点**。

## 3. 数据源、映射与批次

`GET /api/v3/data-sources` → 复用 v2 的 `data_source`（医院范围）。

`GET /api/v3/source-mappings?sourceCode=` → `{items:[{sourceCode,fields,codeMap,version,updatedAt}]}`

`PUT /api/v3/source-mappings`（review_office）
`{sourceCode, fields:{metricCode,deptCode,numerator,denominator,value,unit}, codeMap:{metric:{别名→编码}, dept:{别名→科室}}}`
- 必填 `metricCode`；其余字段可选，缺失时该列不参与计算。
- 未配置映射即导入 → 400 `mapping_incomplete`。

`POST /api/v3/ingestion/batches`（review_office / indicator_admin）
`{sourceCode, period:"YYYY-MM", rows:[{...原始列...}]}`
处理顺序：映射 → 编码清洗（`codeMap`）→ 单位校验 → 质量规则 → 入库。
返回 `{batch, issues[], canonicalFacts[]}`；有 `error` 级问题时 `status=rejected`。

质量规则（error）：未知指标编码、必填字段缺失、科室无法映射、单位与定义不一致、分子大于分母、同批重复（指标+科室）、期间重叠（同期已应用批次同指标科室）、异常跳变（与上一已应用期比 >50%，记为 warning）。

`GET /api/v3/ingestion/batches/{id}` → `{batch, issues, facts, trace}`
`POST /api/v3/ingestion/batches/{id}/apply` → 仅 `validated` 可应用；`rejected` → 409 `quality_blocked`；已应用 → 409 `immutable`。应用时按定义计算该期（及各科室）结果并生成预警。
`POST /api/v3/ingestion/batches/{id}/replay` → 用原批次原始行生成新批次，重新走映射与质量校验。

`GET /api/v3/lineage/results/{resultId}` → `{nodes:[{type,id,label}], edges:[{from,to,relation}]}`，覆盖 source → mapping → batch → fact → result → alert/improvement → report。

## 4. 公式语义（受控）

| formulaType | 计算 | 无有效输入 |
| --- | --- | --- |
| `ratio` | 单条事实 `numerator/denominator`，`unit=%` 时乘 100 | `not_applicable` |
| `weighted_ratio` | `Σnumerator/Σdenominator`（跨科室），`%` 时乘 100 | `not_applicable` |
| `month_avg` | 期间内各月值算术平均 | `not_applicable` |
| `year_end_snapshot` | 年度内最新周期值（不累加） | `not_applicable` |
| `drg_formula` | `Σnumerator/Σdenominator`，`numerator` 为该期加权和、`denominator` 为病例数；**不得退化为平均** | `not_applicable` |
| `trend` | 最近两个周期比较，返回方向与滑坡标记 | `insufficient_periods` |

分母为 0 → `denominator_zero`；单位不一致 → `unit_mismatch`。结果同时返回 `numerator`、`denominator`、`reasonCode`。

## 5. 结果、下钻与预警

`GET /api/v3/metric-results?metricCode=&period=&deptCode=&versionId=` → `{items:[Result]}`
```ts
type Result={id,hospitalId,metricCode,metricName,unit,direction,period,deptCode,
 value:number|null,numerator:number|null,denominator:number|null,light:'green'|'yellow'|'red'|'na',
 target:number|null,warning:number|null,reasonCode,qualityStatus,batchId,definitionId,definitionVersion,
 formulaType,computedAt}
```
灯号：达标绿、预警线内黄、超阈值红；`value=null` 为 `na`。

`GET /api/v3/metric-results/{id}` → 结果 + 定义版本 + 批次 + 公式
`GET /api/v3/metric-results/{id}/drilldown` → `{result, departments:[...], members:{masked:true,items:[]}}`
- 患者级明细在 v3 只返回脱敏占位；`leader`/`observer` 不返回 `members`。

`GET /api/v3/alerts?status=&severity=` → `{items:[Alert]}`
```ts
type Alert={id,metricCode,metricName,deptCode,period,type,severity,status,detail,resultId,
 assignee,dueDate,createdAt,updatedAt}
```
类型：`below_target`、`over_warning`、`worsening`、`jump`、`missing_data`、`quality_open`。
状态机：`open → acknowledged → assigned → resolved`；`open|acknowledged|assigned → ignored`。非法流转 409 `invalid_transition`；`resolved`/`ignored` 为终态。

`POST /api/v3/alerts/{id}/ack | assign | resolve | ignore`
`ack {note}`、`assign {assignee, dueDate, note}`、`resolve {note}`、`ignore {note}`。全部写审计。

## 6. PDCA 与报告

`POST /api/v3/improvements/from-alert` `{alertId, rootCause, action, owner, dueDate}` → 新建整改（状态 `open`），预警转 `assigned`。
`GET /api/v3/improvements?status=` / `GET /api/v3/improvements/{id}`
`POST /api/v3/improvements/{id}/review` `{conclusion:"improved"|"partially_improved"|"not_recovered"|"invalidated", note, evidenceItemId?}`
- 复核必须有结论；`evidenceItemId` 必须是本院已存在材料（复用 v2 `evidence_item`）。
- 完成后状态 `closed`，写入复核结论与时间。

`POST /api/v3/reports` `{versionId, periodFrom:"YYYY-MM", periodTo:"YYYY-MM"}` → 快照 `{id, metricCount, alertCount, improvementCount, createdAt}`
`GET /api/v3/reports/{id}` → 元数据 + 引用清单（批次、定义版本、预警、整改、材料版本）
`GET /api/v3/reports/{id}/document` → DOCX（引用快照生成，后续数据变化不改写）

## 7. 不可变与迁移

- 指标定义按版本追加；批次 `applied` 后不可改；结果与报告为快照；审计事件不提供删除。
- 迁移从版本 4 开始追加：`v2/platform/V4Schema.java` 与 `resources/db/migration/postgresql/V4__v03_data_platform.sql`，不改 v2 表结构与 v1/v2 端点。
- 回滚：代码回退到 v3 交付提交；数据层删除 v4 新增表并移除 `schema_migration` 中 version=4 记录，执行前必须备份。
