# 医院等级评审系统

面向三级医院评审的本地演示系统：从标准条款、任务分派、材料版本，到数据接入、指标计算、预警、PDCA 整改与固定快照报告，形成一条可追溯的纵向闭环。

> **本项目只使用虚构医院与合成数据，不连接任何真实医院系统，不包含真实患者信息，不用于正式评审结论。**

## 版本与能力

| 版本 | 定位 | 关键能力 |
| --- | --- | --- |
| v0.1 | 本地演示 | 14 个演示指标、30 条前置要求、补录审核、条款抽查、模拟等次、DOCX 报告 |
| v0.2 | 生产形态骨架 | 标准包/版本/条款树、任务状态机与幂等、材料版本与 SHA-256、质量批次门、试算快照、审计与请求关联 ID、`demo/prod` 身份边界 |
| v3 | 数据中台与指标引擎 | 受控公式（ratio / weighted_ratio / month_avg / year_end_snapshot / drg_formula / trend）、字段映射与编码清洗、批次阻断与重放、结果下钻与血缘、预警状态机、PDCA 整改、证据版本与报告快照 |

三条链路并存且互不破坏：`/api`（v0.1）、`/api/v2`、`/api/v3`。

## 技术栈

- 后端：Java 17、Spring Boot 3.5、Spring JDBC、H2（本地）/ PostgreSQL（生产形态）
- 前端：Vue 3 + Vite + TypeScript，无 UI 框架，自绘组件与图表
- 测试：JUnit 5（后端）、Vitest（前端）、Node 原生测试与集成检查脚本
- 迁移：追加式迁移（版本 2 → 8），H2 与 PostgreSQL 同构 DDL

## 目录结构

```text
医院等级评审系统/
  backend/            后端服务与测试（v1 演示、v2 生产形态、v3 数据中台）
  frontend/           前端工作台（v1 九个页面、v2 评审作业台、v3 数据中台）
  docs/               设计、契约、验收与接入文档
  scripts/            启动/停止/验证/集成检查脚本
交接文档.md           多 AI 协作的唯一主交接文档（进度、决策、验证记录）
汇报材料/             组会汇报 PPT
```

## 本地运行

前置：JDK 17+、Maven 3.9+、Node.js 20+。

```powershell
# 一键启动（构建前后端并打开浏览器）
启动演示.cmd

# 仅启动已有构建
powershell -ExecutionPolicy Bypass -File 启动演示.ps1 -SkipBuild -NoBrowser

# 停止（数据保留）
停止演示.cmd
```

- 前端：http://127.0.0.1:18173
- 后端健康检查：http://127.0.0.1:18080/api/health
- 数据文件：`医院等级评审系统/backend/.runtime/data/demo.mv.db`（不进版本库）

## 测试与验证

```powershell
cd 医院等级评审系统/backend ; mvn.cmd -o -B -ntp test      # 后端单元与接口测试
cd 医院等级评审系统/frontend ; npm.cmd test -- --run        # 前端逻辑测试
node 医院等级评审系统/scripts/integration-check.mjs         # 端到端集成检查（需服务已启动）
powershell -ExecutionPolicy Bypass -File 医院等级评审系统/scripts/verify.ps1
```

当前状态：后端 94 项、前端 36 项通过；集成检查覆盖 v1/v2/v3 三段链路；PostgreSQL 迁移与备份恢复在临时隔离集群验证。

## 文档索引

- `交接文档.md`：进度、决策、验证记录与已知限制（**先读这个**）
- `医院等级评审系统/docs/next-api-contract.md`：v2 接口契约
- `医院等级评审系统/docs/v3-api-contract.md`：v3 接口契约
- `医院等级评审系统/docs/demo-data-guide.md`：演示数据场景与统计
- `医院等级评审系统/docs/production-readiness.md`：真实接入清单与未验证项

## 数据与合规声明

- 全部数据为合成数据；医院名称、科室、人员、病例均为虚构。
- 不接入 HIS/EMR/LIS/PACS，不抓取任何真实患者信息。
- 指标阈值仅为演示口径，不代表官方评审标准。
- 正式评审口径请以国家卫健委发布的评审标准与本院政策为准。

## 许可证

MIT License，见 [LICENSE](LICENSE)。
