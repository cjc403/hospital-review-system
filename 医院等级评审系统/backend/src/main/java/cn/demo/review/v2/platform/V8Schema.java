package cn.demo.review.v2.platform;

/** 丰富演示场景与跨月报告需要保存完整快照，既有记录原样保留。 */
final class V8Schema {
    private V8Schema() {}
    static final int VERSION = 8;
    static final String[] DDL = {
        "ALTER TABLE v3_report ALTER COLUMN snapshot_json TYPE VARCHAR(4000000)"
    };
}
