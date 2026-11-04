# 本地账号与角色权限契约

用户于 2026-11-03 批准实施。仅本地合成演示。全部预置演示账号初始密码按用户指定设置为 `cjc2003822cjc`，数据库只保存独立盐的 PBKDF2 哈希，后续改密不在重启时重置。prod 不生成演示账号。

## 身份与会话

- 默认所有 `/api` 业务请求须 `Authorization: Bearer <opaque session token>`，健康、登录、演示账号目录例外。令牌随机生成，仅存 SHA-256，8 小时过期；账号状态、角色和会话版本逐请求核对。退出立即撤销，停用/改密/重置密码/改角色/范围立即使旧会话失效。
- `X-Demo-Role` 不是登录凭据，默认不接受其授权；为旧契约隔离测试保留显式 `review.auth.legacy-demo-headers=true`，仅 demo 可启用；正常启动和集成不启用。prod 始终拒绝演示角色头。不能通过该头提升已登录身份。
- v1/v2/v3 业务端点与业务响应保留，认证改动以本补充契约为准。v1 由登录上下文映射旧显示角色，医院 H002 不读取 H001 的聚合快照。科室范围按账号实际科室过滤，不能固定 CARD。v1 科室补录只提交本科室待审值，不改变全院值。
- `POST /api/auth/login` `{username,password}` → `{token,expiresAt,user:User}`，失败统一 401；连续失败 5 次暂时锁定 60 秒。
- `GET /api/auth/me` → `User`；`POST /api/auth/logout` → `{ok:true}`。
- `POST /api/auth/password` `{currentPassword,newPassword}` → `{ok:true}`，成功后当前会话也失效，重新登录。
- `GET /api/auth/demo-accounts`（demo）→ `{items:[{username,displayName,roleCode,roleName,hospitalId,hospitalName,deptCode,deptName}]}`，不返回密码/哈希/令牌。prod 返回空目录。
- `User={id,username,displayName,roleCode,roleName,hospitalId,hospitalName,deptCode,deptName,permissions:string[],legacyRole:'quality'|'leader'|'department'|'information',clauseCodes:string[],status}`。

## 九角色

| roleCode | 名称 | 能力与范围 |
| --- | --- | --- |
| system_admin | 系统管理员 | 本医院账号/角色配置、组织读取、审计；不自动取得业务写权限 |
| review_office | 评审办管理员 | 本医院全院业务统筹、标准、任务、材料、批次、试算和报告 |
| leader | 院级领导 | 本医院汇总、任务、指标与报告只读，无成员明细和写权限 |
| dept_head | 科室负责人 | 本科室任务推进/审核、材料上传审核、预警确认及整改跟踪 |
| dept_liaison | 科室联络员 | 本科室任务推进、上传材料、提交补录，无审核权限 |
| quality_reviewer | 职能质控人员 | 授权科室内任务/材料审核与整改复核，不管理账号或标准 |
| expert | 内审/预评专家 | 指定科室及条款的任务、证据读取与审核；不读取全院结果/报告或修改标准 |
| indicator_admin | 数据管理员 | 本院数据源、映射、质量批次、指标读取及计算；不审核材料或管理账号 |
| auditor | 审计只读人员 | 本医院审计及版本汇总，只读，不获成员明细 |

权限使用集中 `RolePermissions`，返回给前端同源控制菜单和按钮。权限示例 `task.read/task.assign/task.progress/task.review/evidence.read/evidence.upload/evidence.review/metric.read/member.read/batch.read/batch.write/mapping.write/improvement.read/improvement.write/improvement.review/report.read/report.write/standard.read/standard.write/assessment.read/assessment.write/audit.read/account.manage/manual.submit/legacy.write`。

## 管理与工作台

- `GET /api/auth/roles` → `{items:[{code,name,permissions:string[]}]}`。
- `GET /api/auth/accounts` → `{items:User[]}`，仅 `account.manage`，强制本院。
- `POST /api/auth/accounts` `{username,displayName,roleCode,deptCode?,clauseCodes?:string[],password?}` → `User`；默认密码为用户约定的演示初始密码。科室类必须有效科室；专家必须至少一个有效条款。账号名全局唯一，创建账号不能越医院。禁止公开注册。
- `PATCH /api/auth/accounts/{id}` `{displayName?,roleCode?,deptCode?,clauseCodes?,status?:'active'|'disabled'}` → `User`，仅本院系统管理员；不能停用/降级自己或最后一个活跃系统管理员。
- `POST /api/auth/accounts/{id}/reset-password` `{password?}` → `{ok:true}`，重置后旧会话失效，不返回密码哈希。
- `GET /api/auth/workbench` → `{tasks:Task[],alerts:Alert[],improvements:Improvement[],stats:{tasks,alerts,improvements,openTasks,openAlerts,openImprovements,accounts?}}`，按角色权限、医院、实际科室和专家条款过滤。`open*` 为授权范围全部未完成数量，不受预览列表条数上限影响。
- `GET /api/auth/audit?limit=100` → `{items:[...]}`，仅 `audit.read`，医院范围。
- 按2026-11-03用户新要求，登录页面及账号姓名采用正式业务文案，移除演示/合成标注；各角色进入“我的工作台”，显示真实授权待办与快捷入口。系统管理员有账号管理；导航和按钮按权限显示。刷新恢复会话，401 清空身份和工作区，退出清空敏感缓存。

## 保留和验收

V9 只追加账号/会话表，不改已交付迁移或平台合并字段枚举。全部账号位于 demo seed，约 32 个，H001 六科室多账号和 H002 隔离账号。没有真实身份或真实医院数据。全部数据库写入事件可追溯，不记录口令/令牌。验收覆盖九角色登录、旧接口绕过、跨科室/医院、专家条款、禁用/改密/退出失效、越权管理、现有业务门槛及浏览器登录/退出/角色界面。

## 当前身份接入边界

本次只交付本地账号和会话。Bearer 由 `AccountService` 验证；历史 `TokenAuthenticator` 外部适配器尚未接入新的账号、角色和 v1 兼容边界，不能直接声称替换该类即可完成 OIDC/LDAP。prod 不生成演示账号，拒绝演示账号及其令牌，也始终拒绝演示角色头。真实统一身份认证须另建接入契约和独立权限验收。
