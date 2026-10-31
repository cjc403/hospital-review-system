package cn.demo.review.v2.platform;

/**
 * v0.5：整改项目补充来源与前后指标，用于「复核效果」和报告对比。
 * 只追加列，不改已有列；H2 与 PostgreSQL 都支持 ADD COLUMN IF NOT EXISTS。
 */
final class V5Schema {
    private V5Schema() {}

    static final int VERSION = 5;

    static final String[] DDL = {
        "ALTER TABLE v3_improvement ADD COLUMN IF NOT EXISTS source_type VARCHAR(30)",
        "ALTER TABLE v3_improvement ADD COLUMN IF NOT EXISTS source_id VARCHAR(80)",
        "ALTER TABLE v3_improvement ADD COLUMN IF NOT EXISTS target_value DOUBLE PRECISION",
        "ALTER TABLE v3_improvement ADD COLUMN IF NOT EXISTS baseline_value DOUBLE PRECISION",
        "ALTER TABLE v3_improvement ADD COLUMN IF NOT EXISTS latest_value DOUBLE PRECISION"
    };
}
