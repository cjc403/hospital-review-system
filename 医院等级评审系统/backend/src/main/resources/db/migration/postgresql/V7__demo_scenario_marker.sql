CREATE TABLE IF NOT EXISTS demo_seed_marker (
    scenario_code VARCHAR(80) NOT NULL,
    scenario_version INTEGER NOT NULL,
    seed BIGINT NOT NULL,
    summary_json VARCHAR(4000) NOT NULL,
    created_at VARCHAR(64) NOT NULL,
    PRIMARY KEY (scenario_code, scenario_version)
);
CREATE INDEX IF NOT EXISTS idx_demo_seed_marker_code ON demo_seed_marker (scenario_code);
