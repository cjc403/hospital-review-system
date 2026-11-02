package cn.demo.review.demo;

/** 供种子、测试和验收记录使用的确定性演示数据统计。 */
public record DemoScenarioStats(
        int hospitals,
        int departments,
        int metrics,
        int periods,
        int appliedBatches,
        int rejectedBatches,
        int facts,
        int results,
        int alertKinds,
        int alerts,
        int improvements,
        int evidenceItems,
        int reports) {
}
