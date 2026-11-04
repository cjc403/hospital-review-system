package cn.demo.review.v2.platform;

/** 本地账号与可撤销会话，追加迁移，不覆盖业务历史。 */
final class V9Schema {
    static final int VERSION=9;
    static final String[] DDL={
        "CREATE TABLE IF NOT EXISTS auth_account (id VARCHAR(64) PRIMARY KEY, username VARCHAR(80) NOT NULL UNIQUE, display_name VARCHAR(120) NOT NULL, hospital_id VARCHAR(32) NOT NULL, dept_code VARCHAR(32), role_code VARCHAR(40) NOT NULL, clause_codes_json VARCHAR(4000) NOT NULL, password_hash VARCHAR(500) NOT NULL, status VARCHAR(20) NOT NULL, session_version BIGINT NOT NULL, failed_attempts INT NOT NULL, locked_until BIGINT NOT NULL, is_demo INT NOT NULL, created_at VARCHAR(64), updated_at VARCHAR(64))",
        "CREATE INDEX IF NOT EXISTS idx_auth_account_hospital ON auth_account (hospital_id, role_code, status)",
        "CREATE TABLE IF NOT EXISTS auth_session (token_hash VARCHAR(64) PRIMARY KEY, account_id VARCHAR(64) NOT NULL, session_version BIGINT NOT NULL, expires_at BIGINT NOT NULL, created_at BIGINT NOT NULL, revoked INT NOT NULL)",
        "CREATE INDEX IF NOT EXISTS idx_auth_session_account ON auth_session (account_id, expires_at)"
    };
}
