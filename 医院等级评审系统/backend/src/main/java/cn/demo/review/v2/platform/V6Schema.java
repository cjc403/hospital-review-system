package cn.demo.review.v2.platform;

/** v0.6：保留数据源字段映射历史，保证批次重放使用原映射版本。 */
final class V6Schema {
    private V6Schema() {}

    static final int VERSION = 6;

    static final String[] DDL = {
        "CREATE TABLE IF NOT EXISTS source_mapping_history ("
            + " id VARCHAR(64) PRIMARY KEY, hospital_id VARCHAR(32) NOT NULL, source_code VARCHAR(60) NOT NULL,"
            + " mapping_json VARCHAR(4000) NOT NULL, version_no INT NOT NULL, updated_by VARCHAR(64), updated_at VARCHAR(64),"
            + " UNIQUE (hospital_id, source_code, version_no))",
        "CREATE INDEX IF NOT EXISTS idx_mapping_history_scope ON source_mapping_history (hospital_id, source_code, version_no)"
    };
}
