package cn.demo.review.v2.platform;

/**
 * v0.3 结构变更（追加迁移）：前置结论与条款抽查样本。
 * 已发布的版本 2 不改动，版本 3 只新增表。
 */
final class V3Schema {
    private V3Schema() {}

    static final int VERSION = 3;

    static final String[] DDL = {
        "CREATE TABLE IF NOT EXISTS prereq_conclusion ("
            + " hospital_id VARCHAR(32) NOT NULL, version_id VARCHAR(64) NOT NULL, code VARCHAR(60) NOT NULL,"
            + " compliance VARCHAR(20) NOT NULL, workflow VARCHAR(20) NOT NULL, statement VARCHAR(2000),"
            + " updated_by VARCHAR(64), updated_at VARCHAR(64),"
            + " PRIMARY KEY (hospital_id, version_id, code))",
        "CREATE TABLE IF NOT EXISTS audit_sample ("
            + " id VARCHAR(64) PRIMARY KEY, hospital_id VARCHAR(32) NOT NULL, version_id VARCHAR(64) NOT NULL,"
            + " period_code VARCHAR(7) NOT NULL, sample_count INT, error_count INT, error_rate DOUBLE PRECISION,"
            + " created_by VARCHAR(64), created_at VARCHAR(64))",
        "CREATE INDEX IF NOT EXISTS idx_audit_sample_scope ON audit_sample (hospital_id, created_at)"
    };
}
