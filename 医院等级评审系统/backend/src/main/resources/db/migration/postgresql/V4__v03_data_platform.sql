-- v0.3 数据中台（v3）关系模型：追加迁移，不修改 V2/V3 脚本与 v1/v2 表。
-- 已在真实 PostgreSQL 16.4（临时隔离集群）验证，命令见 docs/production-readiness.md。

CREATE TABLE IF NOT EXISTS metric_definition (
    id VARCHAR(64) PRIMARY KEY,
    standard_version_id VARCHAR(64) NOT NULL,
    code VARCHAR(60) NOT NULL,
    name VARCHAR(200) NOT NULL,
    unit VARCHAR(20),
    direction VARCHAR(10) NOT NULL,
    owner_scope VARCHAR(120),
    formula_type VARCHAR(40) NOT NULL,
    aggregation VARCHAR(40),
    target_value DOUBLE PRECISION,
    warning_value DOUBLE PRECISION,
    version_no INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    effective_from VARCHAR(10),
    created_at VARCHAR(64),
    created_by VARCHAR(64),
    UNIQUE (standard_version_id, code, version_no)
);
CREATE INDEX IF NOT EXISTS idx_metric_def_code ON metric_definition (standard_version_id, code);

CREATE TABLE IF NOT EXISTS source_mapping (
    id VARCHAR(64) PRIMARY KEY,
    hospital_id VARCHAR(32) NOT NULL,
    source_code VARCHAR(60) NOT NULL,
    mapping_json VARCHAR(4000) NOT NULL,
    version_no INT NOT NULL,
    updated_by VARCHAR(64),
    updated_at VARCHAR(64),
    UNIQUE (hospital_id, source_code)
);

CREATE TABLE IF NOT EXISTS ingestion_batch (
    id VARCHAR(64) PRIMARY KEY,
    hospital_id VARCHAR(32) NOT NULL,
    source_id VARCHAR(64) NOT NULL,
    standard_version_id VARCHAR(64),
    period_code VARCHAR(7) NOT NULL,
    status VARCHAR(20) NOT NULL,
    record_count INT,
    checksum VARCHAR(64),
    mapping_version INT,
    reject_reason VARCHAR(1000),
    replay_of VARCHAR(64),
    raw_json VARCHAR(16000),
    created_by VARCHAR(64),
    created_at VARCHAR(64),
    applied_at VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS canonical_fact (
    id VARCHAR(64) PRIMARY KEY,
    batch_id VARCHAR(64) NOT NULL,
    metric_code VARCHAR(60) NOT NULL,
    dept_code VARCHAR(32),
    period_code VARCHAR(7) NOT NULL,
    numerator DOUBLE PRECISION,
    denominator DOUBLE PRECISION,
    metric_value DOUBLE PRECISION,
    unit VARCHAR(20),
    excluded_count INT
);
CREATE INDEX IF NOT EXISTS idx_fact_v3_scope ON canonical_fact (metric_code, period_code, dept_code);

CREATE TABLE IF NOT EXISTS v3_quality_issue (
    id VARCHAR(64) PRIMARY KEY,
    batch_id VARCHAR(64) NOT NULL,
    row_no INT,
    rule VARCHAR(60),
    field VARCHAR(60),
    message VARCHAR(500),
    severity VARCHAR(20)
);

CREATE TABLE IF NOT EXISTS v3_trace (
    id VARCHAR(64) PRIMARY KEY,
    batch_id VARCHAR(64) NOT NULL,
    metric_code VARCHAR(60),
    formula_type VARCHAR(40),
    rule_version VARCHAR(40),
    executed_at VARCHAR(64),
    duration_ms BIGINT,
    detail VARCHAR(2000)
);

CREATE TABLE IF NOT EXISTS lineage_edge (
    id VARCHAR(64) PRIMARY KEY,
    hospital_id VARCHAR(32) NOT NULL,
    from_type VARCHAR(30) NOT NULL,
    from_id VARCHAR(80) NOT NULL,
    to_type VARCHAR(30) NOT NULL,
    to_id VARCHAR(80) NOT NULL,
    relation VARCHAR(40)
);
CREATE INDEX IF NOT EXISTS idx_lineage_to ON lineage_edge (hospital_id, to_type, to_id);

CREATE TABLE IF NOT EXISTS metric_result (
    id VARCHAR(64) PRIMARY KEY,
    hospital_id VARCHAR(32) NOT NULL,
    definition_id VARCHAR(64) NOT NULL,
    standard_version_id VARCHAR(64),
    metric_code VARCHAR(60) NOT NULL,
    metric_name VARCHAR(200),
    unit VARCHAR(20),
    direction VARCHAR(10),
    period_code VARCHAR(7) NOT NULL,
    dept_code VARCHAR(32) NOT NULL,
    metric_value DOUBLE PRECISION,
    numerator DOUBLE PRECISION,
    denominator DOUBLE PRECISION,
    light VARCHAR(10),
    target_value DOUBLE PRECISION,
    warning_value DOUBLE PRECISION,
    reason_code VARCHAR(40),
    quality_status VARCHAR(20),
    batch_id VARCHAR(64),
    formula_type VARCHAR(40),
    definition_version INT,
    computed_at VARCHAR(64),
    UNIQUE (hospital_id, metric_code, period_code, dept_code)
);
CREATE INDEX IF NOT EXISTS idx_result_scope ON metric_result (hospital_id, period_code, dept_code);

CREATE TABLE IF NOT EXISTS v3_alert (
    id VARCHAR(64) PRIMARY KEY,
    hospital_id VARCHAR(32) NOT NULL,
    metric_code VARCHAR(60) NOT NULL,
    metric_name VARCHAR(200),
    dept_code VARCHAR(32),
    period_code VARCHAR(7),
    alert_type VARCHAR(40),
    severity VARCHAR(20),
    status VARCHAR(20) NOT NULL,
    detail VARCHAR(1000),
    result_id VARCHAR(64),
    assignee VARCHAR(64),
    due_date VARCHAR(10),
    created_at VARCHAR(64),
    updated_at VARCHAR(64)
);
CREATE INDEX IF NOT EXISTS idx_alert_scope ON v3_alert (hospital_id, status, dept_code);

CREATE TABLE IF NOT EXISTS v3_improvement (
    id VARCHAR(64) PRIMARY KEY,
    hospital_id VARCHAR(32) NOT NULL,
    alert_id VARCHAR(64),
    metric_code VARCHAR(60),
    metric_name VARCHAR(200),
    root_cause VARCHAR(1000),
    action_text VARCHAR(1000),
    owner VARCHAR(64),
    due_date VARCHAR(10),
    status VARCHAR(20) NOT NULL,
    review_conclusion VARCHAR(40),
    review_note VARCHAR(1000),
    evidence_item_id VARCHAR(64),
    closed_at VARCHAR(64),
    created_at VARCHAR(64),
    updated_at VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS v3_report (
    id VARCHAR(64) PRIMARY KEY,
    hospital_id VARCHAR(32) NOT NULL,
    standard_version_id VARCHAR(64),
    period_from VARCHAR(7) NOT NULL,
    period_to VARCHAR(7) NOT NULL,
    snapshot_json VARCHAR(16000),
    created_at VARCHAR(64),
    created_by VARCHAR(64)
);
