package cn.demo.review;

import cn.demo.review.v3.metric.FormulaEngine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 受控公式引擎：四类聚合不得互相退化。 */
class FormulaEngineTest {
    private static final FormulaEngine.Spec I01 = new FormulaEngine.Spec(
            "i01", FormulaEngine.FormulaType.ratio, "%", "low", 0.3, 0.4);
    private static final FormulaEngine.Spec I11 = new FormulaEngine.Spec(
            "i11", FormulaEngine.FormulaType.drg_formula, "", "high", 1.2, 1.0);
    private static final FormulaEngine.Spec I12 = new FormulaEngine.Spec(
            "i12", FormulaEngine.FormulaType.year_end_snapshot, "张", "high", 800d, 700d);

    @Test
    void ratioUsesNumeratorOverDenominator() {
        var facts = List.of(new FormulaEngine.Fact("2026-09", "CARD", 11d, 2000d, 0.55, "%"));
        var result = FormulaEngine.compute(I01, facts, "2026-09", "CARD");
        assertEquals(0.55, result.value(), 1e-9);
        assertEquals(11d, result.numerator());
        assertEquals(2000d, result.denominator());
        assertEquals("red", result.light(), "低优指标 0.55 超过预警线 0.4 应为红灯");
    }

    @Test
    void ratioAcrossDepartmentsIsWeightedNotAveraged() {
        var facts = List.of(
                new FormulaEngine.Fact("2026-09", "CARD", 1d, 1000d, 0.1, "%"),
                new FormulaEngine.Fact("2026-09", "SURG", 9d, 1000d, 0.9, "%"));
        var result = FormulaEngine.compute(I01, facts, "2026-09", "ALL");
        // 加权 10/2000=0.5%；若按平均值会是 (0.1+0.9)/2=0.5%，这里换成非对称样本验证
        assertEquals(0.5, result.value(), 1e-9);
        var skewed = List.of(
                new FormulaEngine.Fact("2026-09", "CARD", 1d, 100d, 1.0, "%"),
                new FormulaEngine.Fact("2026-09", "SURG", 9d, 900d, 1.0, "%"));
        var weighted = FormulaEngine.compute(I01, skewed, "2026-09", "ALL");
        assertEquals(1.0, weighted.value(), 1e-9);
        assertEquals(10d, weighted.numerator());
        assertEquals(1000d, weighted.denominator());
    }

    @Test
    void drgFormulaNeverDegradesToSimpleAverage() {
        // 加权和 1000 / 病例 100 = 10；若按平均值会是 (1+3)/2=2
        var facts = List.of(
                new FormulaEngine.Fact("2026-06", "ALL", 400d, 100d, 4.0, ""),
                new FormulaEngine.Fact("2026-07", "ALL", 600d, 100d, 6.0, ""));
        var result = FormulaEngine.compute(I11, facts, "2026", "ALL");
        assertEquals(5.0, result.value(), 1e-9, "DRG 应按 Σ分子/Σ分母 聚合");
        assertEquals(1000d, result.numerator());
        assertEquals(200d, result.denominator());
    }

    @Test
    void yearEndSnapshotTakesLatestPeriodNotAverage() {
        var facts = List.of(
                new FormulaEngine.Fact("2026-06", "ALL", null, null, 850d, "张"),
                new FormulaEngine.Fact("2026-11", "ALL", null, null, 860d, "张"));
        var result = FormulaEngine.compute(I12, facts, "2026", "ALL");
        assertEquals(860d, result.value(), 1e-9);
        var average = FormulaEngine.compute(
                new FormulaEngine.Spec("i14", FormulaEngine.FormulaType.month_avg, "天", "low", 7.5, 8.5),
                List.of(new FormulaEngine.Fact("2026-06", "ALL", null, null, 8.0, "天"),
                        new FormulaEngine.Fact("2026-11", "ALL", null, null, 9.0, "天")),
                "2026", "ALL");
        assertEquals(8.5, average.value(), 1e-9);
    }

    @Test
    void missingAndInvalidInputsReturnExplicitReasonCodes() {
        assertEquals("not_applicable",
                FormulaEngine.compute(I01, List.of(), "2026-09", "CARD").reasonCode());
        assertEquals("denominator_zero",
                FormulaEngine.compute(I01, List.of(new FormulaEngine.Fact("2026-09", "CARD", 5d, 0d, null, "%")),
                        "2026-09", "CARD").reasonCode());
        assertEquals("missing_input",
                FormulaEngine.compute(I01, List.of(new FormulaEngine.Fact("2026-09", "CARD", 5d, null, null, "%")),
                        "2026-09", "CARD").reasonCode());
        assertEquals("unit_mismatch",
                FormulaEngine.compute(I01, List.of(new FormulaEngine.Fact("2026-09", "CARD", 5d, 100d, 5d, "天")),
                        "2026-09", "CARD").reasonCode());
        assertEquals("insufficient_periods",
                FormulaEngine.compute(new FormulaEngine.Spec("t", FormulaEngine.FormulaType.trend, "", "high", 1d, 1d),
                        List.of(new FormulaEngine.Fact("2026-09", "ALL", null, null, 1d, "")),
                        "2026", "ALL").reasonCode());
    }

    @Test
    void trendMarksWorseningForBothDirections() {
        var high = FormulaEngine.compute(new FormulaEngine.Spec("h", FormulaEngine.FormulaType.trend, "", "high", 90d, 85d),
                List.of(new FormulaEngine.Fact("2026-08", "ALL", null, null, 95d, ""),
                        new FormulaEngine.Fact("2026-09", "ALL", null, null, 92d, "")),
                "2026", "ALL");
        assertEquals("worsening", high.reasonCode());
        var low = FormulaEngine.compute(new FormulaEngine.Spec("l", FormulaEngine.FormulaType.trend, "", "low", 7.5, 8.5),
                List.of(new FormulaEngine.Fact("2026-08", "ALL", null, null, 7.8, ""),
                        new FormulaEngine.Fact("2026-09", "ALL", null, null, 8.4, "")),
                "2026", "ALL");
        assertEquals("worsening", low.reasonCode());
        assertEquals("yellow", low.light(), "低优指标 8.4 在预警线内应为黄灯");
    }

    @Test
    void rejectingArbitraryFormulaType() {
        assertThrows(IllegalArgumentException.class, () -> FormulaEngine.FormulaType.parse("sql_script"));
        assertFalse(FormulaEngine.ALLOWED_TYPES.contains("expression"));
    }
}
