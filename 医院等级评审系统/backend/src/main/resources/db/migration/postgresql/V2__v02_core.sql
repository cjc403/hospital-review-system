-- v0.2 关系模型：PostgreSQL 版（草案，与 H2 的 V2Schema 保持同构）
--
-- 状态：**未在真实 PostgreSQL 实例上执行过**。本地验收只跑 H2。
-- 交付时必须以真实库执行并记录结果，执行前请确认库名是隔离库：
--   createdb review_v02_check
--   psql -d review_v02_check -f V2__v02_core.sql
--   psql -d review_v02_check -c "\dt"
--
-- 约定：
-- - 只用 H2 与 PostgreSQL 都支持的语法；不使用 SERIAL/JSONB/MERGE，主键全部由应用生成。
-- - 已发布后不得修改本文件；结构变更追加 V3__*.sql。
-- - 时间与日期用 VARCHAR 保存 ISO-8601 字符串，避免两种库的时区/格式差异；
--   真实落地如需时间范围查询与索引，应改为 TIMESTAMP 并追加迁移。

CREATE TABLE IF NOT EXISTS schema_migration (
    version INT PRIMARY KEY,
    applied_at VARCHAR(64) NOT NULL
);

CREATE TABLE IF NOT EXISTS hospital (
    id VARCHAR(32) PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    level_name VARCHAR(60)
);

CREATE TABLE IF NOT EXISTS department (
    hospital_id VARCHAR(32) NOT NULL,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(120) NOT NULL,
    PRIMARY KEY (hospital_id, code)
);

CREATE TABLE IF NOT EXISTS standard_package (
    id VARCHAR(64) PRIMARY KEY,
    code VARCHAR(60) NOT NULL UNIQUE,
    title VARCHAR(200) NOT NULL,
    issuer VARCHAR(200),
    source_locator VARCHAR(500)
);

CREATE TABLE IF NOT EXISTS standard_version (
    id VARCHAR(64) PRIMARY KEY,
    package_id VARCHAR(64) NOT NULL,
    version_no INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    published_on VARCHAR(10),
    effective_from VARCHAR(10),
    applies_to VARCHAR(200),
    note VARCHAR(1000),
    checksum VARCHAR(64),
    created_at VARCHAR(64),
    created_by VARCHAR(64),
    UNIQUE (package_id, version_no)
);

CREATE TABLE IF NOT EXISTS standard_chapter (
    version_id VARCHAR(64) NOT NULL,
    code VARCHAR(40) NOT NULL,
    title VARCHAR(200) NOT NULL,
    sort_no INT,
    PRIMARY KEY (version_id, code)
);

CREATE TABLE IF NOT EXISTS standard_clause (
    version_id VARCHAR(64) NOT NULL,
    code VARCHAR(60) NOT NULL,
    chapter_code VARCHAR(40) NOT NULL,
    title VARCHAR(200),
    requirement_text VARCHAR(2000),
    evidence_type VARCHAR(200),
    owner_scope VARCHAR(200),
    evaluation_method VARCHAR(200),
    source_locator VARCHAR(500),
    sort_no INT,
    PRIMARY KEY (version_id, code)
);

CREATE TABLE IF NOT EXISTS indicator_definition (
    version_id VARCHAR(64) NOT NULL,
    code VARCHAR(60) NOT NULL,
    name VARCHAR(200) NOT NULL,
    clause_code VARCHAR(60),
    chapter_code VARCHAR(40),
    dimension VARCHAR(60),
    unit VARCHAR(20),
    direction VARCHAR(10),
    score_type VARCHAR(30),
    annual_agg VARCHAR(30),
    collect_mode VARCHAR(20),
    target_value DOUBLE PRECISION,
    warn_value DOUBLE PRECISION,
    numerator_rule VARCHAR(2000),
    denominator_rule VARCHAR(2000),
    include_rule VARCHAR(2000),
    exclude_rule VARCHAR(2000),
    PRIMARY KEY (version_id, code)
);

CREATE TABLE IF NOT EXISTS prereq_definition (
    version_id VARCHAR(64) NOT NULL,
    code VARCHAR(60) NOT NULL,
    section VARCHAR(120),
    title VARCHAR(200),
    statement_text VARCHAR(2000),
    system_generated INT,
    PRIMARY KEY (version_id, code)
);

CREATE TABLE IF NOT EXISTS review_task (
    id VARCHAR(64) PRIMARY KEY,
    hospital_id VARCHAR(32) NOT NULL,
    version_id VARCHAR(64) NOT NULL,
    clause_code VARCHAR(60) NOT NULL,
    dept_code VARCHAR(32) NOT NULL,
    title VARCHAR(200),
    assignee_post VARCHAR(60),
    priority VARCHAR(20),
    due_date VARCHAR(10),
    status VARCHAR(20) NOT NULL,
    return_reason VARCHAR(1000),
    request_key VARCHAR(120),
    created_by VARCHAR(64),
    created_at VARCHAR(64),
    updated_at VARCHAR(64)
);
CREATE INDEX IF NOT EXISTS idx_task_scope ON review_task (hospital_id, dept_code, status);
CREATE INDEX IF NOT EXISTS idx_task_request ON review_task (hospital_id, request_key);

CREATE TABLE IF NOT EXISTS assignment_request (
    hospital_id VARCHAR(32) NOT NULL,
    request_key VARCHAR(120) NOT NULL,
    task_count INT,
    created_at VARCHAR(64),
    PRIMARY KEY (hospital_id, request_key)
);

CREATE TABLE IF NOT EXISTS evidence_item (
    id VARCHAR(64) PRIMARY KEY,
    hospital_id VARCHAR(32) NOT NULL,
    owner_type VARCHAR(20) NOT NULL,
    owner_id VARCHAR(60) NOT NULL,
    title VARCHAR(200) NOT NULL,
    material_type VARCHAR(60),
    status VARCHAR(20) NOT NULL,
    valid_until VARCHAR(10),
    created_by VARCHAR(64),
    created_at VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS evidence_version (
    id VARCHAR(64) PRIMARY KEY,
    item_id VARCHAR(64) NOT NULL,
    version_no INT NOT NULL,
    file_name VARCHAR(200) NOT NULL,
    content_type VARCHAR(100),
    size_bytes BIGINT,
    sha256 VARCHAR(64) NOT NULL,
    storage_key VARCHAR(120) NOT NULL,
    uploaded_by VARCHAR(64),
    uploaded_at VARCHAR(64),
    UNIQUE (item_id, version_no)
);
CREATE INDEX IF NOT EXISTS idx_evidence_item_scope ON evidence_item (hospital_id, owner_type, owner_id);

CREATE TABLE IF NOT EXISTS data_source (
    id VARCHAR(64) PRIMARY KEY,
    hospital_id VARCHAR(32) NOT NULL,
    code VARCHAR(60) NOT NULL,
    name VARCHAR(200) NOT NULL,
    kind VARCHAR(20) NOT NULL,
    active INT NOT NULL,
    UNIQUE (hospital_id, code)
);

CREATE TABLE IF NOT EXISTS collection_batch (
    id VARCHAR(64) PRIMARY KEY,
    hospital_id VARCHAR(32) NOT NULL,
    source_id VARCHAR(64) NOT NULL,
    period_code VARCHAR(7) NOT NULL,
    status VARCHAR(20) NOT NULL,
    record_count INT,
    checksum VARCHAR(64),
    reject_reason VARCHAR(1000),
    replay_of VARCHAR(64),
    created_by VARCHAR(64),
    created_at VARCHAR(64),
    applied_at VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS metric_fact (
    id VARCHAR(64) PRIMARY KEY,
    batch_id VARCHAR(64) NOT NULL,
    indicator_code VARCHAR(60) NOT NULL,
    period_code VARCHAR(7) NOT NULL,
    numerator DOUBLE PRECISION,
    denominator DOUBLE PRECISION,
    excluded_count INT,
    metric_value DOUBLE PRECISION,
    unit VARCHAR(20)
);
CREATE INDEX IF NOT EXISTS idx_fact_period ON metric_fact (indicator_code, period_code);

CREATE TABLE IF NOT EXISTS calculation_trace (
    id VARCHAR(64) PRIMARY KEY,
    batch_id VARCHAR(64) NOT NULL,
    rule_version VARCHAR(64),
    engine VARCHAR(64),
    executed_at VARCHAR(64),
    duration_ms BIGINT,
    detail VARCHAR(2000)
);

CREATE TABLE IF NOT EXISTS quality_issue (
    id VARCHAR(64) PRIMARY KEY,
    batch_id VARCHAR(64) NOT NULL,
    row_no INT,
    rule VARCHAR(60),
    field VARCHAR(60),
    message VARCHAR(500),
    severity VARCHAR(20)
);

CREATE TABLE IF NOT EXISTS score_run (
    id VARCHAR(64) PRIMARY KEY,
    hospital_id VARCHAR(32) NOT NULL,
    version_id VARCHAR(64) NOT NULL,
    cycle_start INT NOT NULL,
    cycle_end INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    grade VARCHAR(20),
    conform_count INT,
    included_count INT,
    ratio DOUBLE PRECISION,
    deferred_reason VARCHAR(1000),
    snapshot_json VARCHAR(16000),
    created_by VARCHAR(64),
    created_at VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS score_item (
    id VARCHAR(64) PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    indicator_code VARCHAR(60) NOT NULL,
    name VARCHAR(200),
    unit VARCHAR(20),
    year_values VARCHAR(500),
    merged_value DOUBLE PRECISION,
    conform INT,
    reason_code VARCHAR(40)
);
CREATE INDEX IF NOT EXISTS idx_score_item_run ON score_item (run_id);

CREATE TABLE IF NOT EXISTS audit_event (
    id VARCHAR(64) PRIMARY KEY,
    hospital_id VARCHAR(32) NOT NULL,
    actor VARCHAR(64) NOT NULL,
    action_name VARCHAR(80) NOT NULL,
    object_type VARCHAR(40),
    object_id VARCHAR(80),
    detail VARCHAR(1000),
    request_id VARCHAR(60),
    created_at VARCHAR(64)
);
CREATE INDEX IF NOT EXISTS idx_audit_scope ON audit_event (hospital_id, created_at);
