-- v0.5：整改项目补充来源与前后指标（只追加列）
-- 已在真实 PostgreSQL 16.4 临时隔离集群验证。

ALTER TABLE v3_improvement ADD COLUMN IF NOT EXISTS source_type VARCHAR(30);
ALTER TABLE v3_improvement ADD COLUMN IF NOT EXISTS source_id VARCHAR(80);
ALTER TABLE v3_improvement ADD COLUMN IF NOT EXISTS target_value DOUBLE PRECISION;
ALTER TABLE v3_improvement ADD COLUMN IF NOT EXISTS baseline_value DOUBLE PRECISION;
ALTER TABLE v3_improvement ADD COLUMN IF NOT EXISTS latest_value DOUBLE PRECISION;
