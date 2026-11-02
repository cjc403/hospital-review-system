package cn.demo.review.v2.platform;

/** v0.7：记录可重复演示场景的种子版本和统计摘要。 */
final class V7Schema {
    private V7Schema() {}

    static final int VERSION = 7;

    static final String[] DDL = {
        "CREATE TABLE IF NOT EXISTS demo_seed_marker ("
            + " scenario_code VARCHAR(80) NOT NULL, scenario_version INT NOT NULL, seed BIGINT NOT NULL,"
            + " summary_json VARCHAR(4000) NOT NULL, created_at VARCHAR(64) NOT NULL,"
            + " PRIMARY KEY (scenario_code, scenario_version))",
        "CREATE INDEX IF NOT EXISTS idx_demo_seed_marker_code ON demo_seed_marker (scenario_code)"
    };
}
