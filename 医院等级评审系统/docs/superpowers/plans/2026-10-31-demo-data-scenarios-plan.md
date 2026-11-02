# 全量演示数据场景包 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 在不改变 v1/v2/v3 既有契约的前提下，建立可重复、可统计、跨模块关联的高质量合成演示数据场景。

**Architecture:** 新增 demo/seed 编排器，使用固定 seed 和场景 marker 幂等补齐 v2/v3 规范化数据；通过 DemoService 的版本化 enrich 逻辑扩充 v1 序列化状态。v3 批次通过正式 IngestionService 计算结果、预警和血缘，v2 任务/材料/报告通过已有服务或同等约束写入。

**Tech Stack:** Java 17 source / Spring Boot / JdbcTemplate / Jackson / H2 + PostgreSQL 同构迁移；Vue 不新增页面，使用现有页面展示扩充数据。

**Spec:** `docs/superpowers/specs/2026-10-31-demo-data-scenarios-design.md`

## Global Constraints

- 全部医院、患者和业务数据必须是虚构合成数据。
- 不改 v1/v2/v3 既有端点和响应契约；新结构先追加迁移。
- `review.profile=prod` 不写入演示场景。
- 已发布标准、已应用批次、材料版本、试算快照、审计事件只追加，不修改删除。
- 验证必须使用临时 H2/临时附件目录，不污染用户运行库。
- 页面持续标注“演示环境 / 合成数据”。

## Review Focus

- 重启幂等：同一场景重复启动不翻倍，marker 与汇总稳定。
- 周期血缘：每个 v3 结果只来自所属周期批次，重放仍使用批次映射版本。
- 异常覆盖：质量阻断批次不产生结果，异常/缺失/跳变预警可解释。
- 权限覆盖：H001/H002、ALL/CARD 等医院与科室边界仍由服务端过滤。
- 业务闭环：任务、材料、预警、整改、报告之间存在可追溯关联，旧报告可稳定下载。

---

### Task 1: 场景 marker 迁移和生成统计

**Files:**
- Create: `backend/src/main/java/cn/demo/review/v2/platform/V7Schema.java`
- Create: `backend/src/main/resources/db/migration/postgresql/V7__demo_scenario_marker.sql`
- Modify: `backend/src/main/java/cn/demo/review/v2/platform/SchemaMigrator.java`
- Test: `backend/src/test/java/cn/demo/review/SchemaMigrationTest.java`

- [ ] 写失败测试：迁移版本包含 7，`demo_seed_marker` 表存在，重复迁移不重复插入结构。
- [ ] 实现追加式 V7 DDL：`scenario_code`、`scenario_version`、`seed`、`summary_json`、`created_at` 唯一约束。
- [ ] 运行 `mvn.cmd -o -B -ntp -Dtest=SchemaMigrationTest test`，确认先失败后通过。

### Task 2: 确定性场景生成器

**Files:**
- Create: `backend/src/main/java/cn/demo/review/demo/DemoScenarioCatalog.java`
- Create: `backend/src/main/java/cn/demo/review/demo/DemoScenarioStats.java`
- Test: `backend/src/test/java/cn/demo/review/DemoScenarioCatalogTest.java`

- [ ] 写固定 seed 测试：同一周期/指标/科室生成的值稳定，低优/高优指标趋势方向正确，异常场景分类稳定。
- [ ] 实现 `DemoScenarioCatalog`：定义 `group-demo-2026`、seed `20261031`、12 个应用周期、14 指标、6 科室、ALL 聚合和六类叙事场景。
- [ ] 实现统计对象，至少返回医院、科室、指标、周期、批次、事实、结果、预警、整改、材料、报告计数。
- [ ] 运行生成器单测，验证最小规模和异常输入。

### Task 3: v3 多周期批次、质量门和结果链

**Files:**
- Create: `backend/src/main/java/cn/demo/review/demo/DemoScenarioSeeder.java`
- Modify: `backend/src/main/java/cn/demo/review/v3/platform/V3Seeder.java`
- Test: `backend/src/test/java/cn/demo/review/V3ApiTest.java`

- [ ] 写失败测试：临时库首次启动后 v3 应用批次不少于 12、阻断批次不少于 4、结果不少于 500、预警至少覆盖 5 类；重复运行计数不变。
- [ ] 通过正式 `IngestionService` 逐周期写入 i01/i03/i04/i05/i11/i12 等代表指标和全部科室，保留应用批次、事实、结果、trace、lineage。
- [ ] 生成未知指标、重复事实、分母为零、单位不符、未映射科室、周期重叠阻断批次，并断言阻断批次无结果。
- [ ] 让趋势场景产生 worsening/jump/missing/target 预警；默认场景保留多状态预警和整改来源。
- [ ] 用 `demo_seed_marker` 实现幂等，向 marker 写入统计摘要。
- [ ] 运行 v3 API 回归和新增统计测试。

### Task 4: v2 任务、材料、前置和试算场景

**Files:**
- Modify: `backend/src/main/java/cn/demo/review/v2/platform/V2Seeder.java`
- Create: `backend/src/main/java/cn/demo/review/demo/DemoV2ScenarioSeeder.java`
- Test: `backend/src/test/java/cn/demo/review/V2ApiTest.java`

- [ ] 写失败测试：H001 至少 30 条任务、12 个材料条目、6 个抽查、8 个前置结论、4 个试算快照，状态分布满足设计。
- [ ] 使用正式标准版本、任务和证据约束创建跨章节/科室任务；材料使用合成 TXT/DOCX 字节并生成多个不可变版本。
- [ ] 创建已审核、待审核、被退回材料，以及有证据/无证据整改和前置延期原因。
- [ ] 创建不同周期试算与稳定报告引用，禁止生成真实患者标识。
- [ ] 运行 v2 API 和报告回归。

### Task 5: v1 序列化状态 enrich

**Files:**
- Create: `backend/src/main/java/cn/demo/review/demo/DemoV1Enricher.java`
- Modify: `backend/src/main/java/cn/demo/review/service/DemoService.java`
- Modify: `backend/src/main/java/cn/demo/review/domain/SyntheticData.java`
- Test: `backend/src/test/java/cn/demo/review/SyntheticDataTest.java`

- [ ] 写失败测试：默认状态每指标至少 24 个趋势点，任务/整改/病案差异/材料/报告数量和状态覆盖达到设计最低值。
- [ ] 对旧 `schemaVersion` 做追加式 enrich；不删除用户已有追加对象，不覆盖已审核材料和历史报告。
- [ ] 扩展确定性趋势、部门差异、改进前后值、政策登记和合成脱敏成员样本。
- [ ] 运行 SyntheticDataTest 和 API 回归。

### Task 6: 统计接口、README 和验收记录

**Files:**
- Modify: `医院等级评审系统/README.md`
- Modify: `交接文档.md`
- Modify: `docs/production-readiness.md`
- Test: `scripts/integration-check.mjs` 或新增隔离统计断言

- [ ] 写集成断言：默认首页、v2 作业台和 v3 四个页签不为空，统计满足最低数量，角色过滤继续有效。
- [ ] 更新演示路线，加入“稳态→恶化→质量阻断→整改闭环→报告快照”讲解顺序。
- [ ] 运行后端、前端、隔离集成和全量 verify，记录真实统计。
- [ ] 记录未验证项：真实数据源、真实身份、目标 PostgreSQL、性能容量和完整浏览器业务链路。


## 实施收尾（2026-10-31）

Task 1–6 已实施，原始步骤保留作设计历史，不以原计划中的检查框代替实测记录。

| 任务 | 最终交付与验收 |
| --- | --- |
| 1 | V7 marker + V8 报告容量；迁移重复执行、H2 PostgreSQL 模式验证通过 |
| 2 | 固定种子场景目录；统计模型为验收下限，实际数量另用 JDBC/API 统计 |
| 3 | 正式接入多月全指标、六类阻断、结果/预警/血缘；新库重启与旧库多定义版本升级回归通过 |
| 4 | 正式服务创建跨科室任务、真实附件版本、抽查、试算、三条有证据的整改闭环与三份完整报告 |
| 5 | v1 追加 enrich；幂等、旧报告不变、附件落盘、前置/抽查延期回归通过 |
| 6 | README、演示指南、交接 T17、运行库实际统计；94/36/3 测试及 27 组隔离集成、全量 verify 通过，浏览器导航与数据走查完成 |

实现调整：不新增统计端点，统计保存在 marker 与运行验收 JSON；v3 沿用 open/closed 整改契约，五状态演示用于预警；旧库既有事实范围保留；真实 PostgreSQL、容量和 Word 分页未验证。用户已授权直接完成，不再要求设计审核。
