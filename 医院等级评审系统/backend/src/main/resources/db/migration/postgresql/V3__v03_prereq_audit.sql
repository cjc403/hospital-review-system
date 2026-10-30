-- v0.3：前置结论与条款抽查（追加迁移，不修改已发布的 V2 脚本）
-- 状态：已在真实 PostgreSQL 16.4（临时隔离集群）执行验证；执行方式见 docs/production-readiness.md。

CREATE TABLE IF NOT EXISTS prereq_conclusion (
    hospital_id VARCHAR(32) NOT NULL,
    version_id VARCHAR(64) NOT NULL,
    code VARCHAR(60) NOT NULL,
    compliance VARCHAR(20) NOT NULL,
    workflow VARCHAR(20) NOT NULL,
    statement VARCHAR(2000),
    updated_by VARCHAR(64),
    updated_at VARCHAR(64),
    PRIMARY KEY (hospital_id, version_id, code)
);

CREATE TABLE IF NOT EXISTS audit_sample (
    id VARCHAR(64) PRIMARY KEY,
    hospital_id VARCHAR(32) NOT NULL,
    version_id VARCHAR(64) NOT NULL,
    period_code VARCHAR(7) NOT NULL,
    sample_count INT,
    error_count INT,
    error_rate DOUBLE PRECISION,
    created_by VARCHAR(64),
    created_at VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_audit_sample_scope ON audit_sample (hospital_id, created_at);
