CREATE TABLE IF NOT EXISTS source_mapping_history (
    id VARCHAR(64) PRIMARY KEY,
    hospital_id VARCHAR(32) NOT NULL,
    source_code VARCHAR(60) NOT NULL,
    mapping_json VARCHAR(4000) NOT NULL,
    version_no INTEGER NOT NULL,
    updated_by VARCHAR(64),
    updated_at VARCHAR(64),
    UNIQUE (hospital_id, source_code, version_no)
);
CREATE INDEX IF NOT EXISTS idx_mapping_history_scope
    ON source_mapping_history (hospital_id, source_code, version_no);
