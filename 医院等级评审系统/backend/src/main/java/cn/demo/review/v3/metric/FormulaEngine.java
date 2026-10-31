package cn.demo.review.v3.metric;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 受控公式引擎：只接受固定类型，不接受任意 SQL 或脚本。
 * 所有方法都是纯函数，便于针对 i01/i03/i11/i12 的聚合差异做单测。
 */
public final class FormulaEngine {
    private FormulaEngine() {}

    public static final Set<String> ALLOWED_TYPES = Set.of(
            "ratio", "weighted_ratio", "month_avg", "year_end_snapshot", "drg_formula", "trend");

    public enum FormulaType {
        ratio, weighted_ratio, month_avg, year_end_snapshot, drg_formula, trend;

        public static FormulaType parse(String raw) {
            if (raw == null) throw new IllegalArgumentException("缺少 formulaType");
            for (FormulaType type : values()) if (type.name().equals(raw)) return type;
            throw new IllegalArgumentException("不支持的 formulaType：" + raw);
        }
    }

    public record Spec(String code, FormulaType type, String unit, String direction,
                       Double target, Double warning) {}

    public record Fact(String period, String deptCode, Double numerator, Double denominator,
                       Double value, String unit) {}

    public record Result(Double value, Double numerator, Double denominator, String reasonCode, String light) {}

    /**
     * @param period 目标周期；`YYYY-MM` 取当月，`YYYY` 取整年（用于年度聚合）
     * @param deptCode `ALL` 表示跨科室聚合，否则只取该科室
     */
    public static Result compute(Spec spec, List<Fact> facts, String period, String deptCode) {
        if (spec == null) return notApplicable("missing_definition");
        List<Fact> scoped = facts.stream()
                .filter(f -> inPeriod(f.period(), period))
                .filter(f -> "ALL".equals(deptCode) || deptCode.equals(f.deptCode()))
                .sorted(Comparator.comparing(Fact::period))
                .toList();
        if (scoped.isEmpty()) return notApplicable("not_applicable");
        for (Fact fact : scoped) {
            if (spec.unit() != null && !spec.unit().isBlank() && fact.unit() != null
                    && !fact.unit().isBlank() && !spec.unit().equals(fact.unit())) {
                return new Result(null, null, null, "unit_mismatch", "na");
            }
        }
        return switch (spec.type()) {
            case ratio -> ratio(spec, scoped);
            case weighted_ratio, drg_formula -> weighted(spec, scoped);
            case month_avg -> monthAverage(spec, scoped, period);
            case year_end_snapshot -> yearEnd(spec, scoped);
            case trend -> trend(spec, scoped);
        };
    }

    private static boolean inPeriod(String factPeriod, String period) {
        if (factPeriod == null || period == null) return false;
        if (period.length() == 4) return factPeriod.startsWith(period + "-");
        return factPeriod.equals(period);
    }

    private static Result ratio(Spec spec, List<Fact> facts) {
        // 单条事实：分子/分母；跨科室时按加权处理，避免"平均值的平均"
        if (facts.size() > 1) return weighted(spec, facts);
        Fact fact = facts.get(0);
        if (fact.numerator() == null || fact.denominator() == null) return notApplicable("missing_input");
        if (fact.denominator() == 0) return new Result(null, fact.numerator(), 0d, "denominator_zero", "na");
        double value = percentScale(spec, fact.numerator(), fact.denominator());
        return new Result(round(value), fact.numerator(), fact.denominator(), "pass", light(spec, value));
    }

    private static Result weighted(Spec spec, List<Fact> facts) {
        double numerator = 0, denominator = 0;
        for (Fact fact : facts) {
            if (fact.numerator() == null || fact.denominator() == null) return notApplicable("missing_input");
            numerator += fact.numerator();
            denominator += fact.denominator();
        }
        if (denominator == 0) return new Result(null, numerator, denominator, "denominator_zero", "na");
        double value = percentScale(spec, numerator, denominator);
        return new Result(round(value), numerator, denominator, "pass", light(spec, value));
    }

    private static Result monthAverage(Spec spec, List<Fact> facts, String period) {
        List<Double> values = new ArrayList<>();
        for (Fact fact : facts) {
            Double value = fact.value() != null ? fact.value()
                    : (fact.numerator() != null && fact.denominator() != null && fact.denominator() != 0
                        ? percentScale(spec, fact.numerator(), fact.denominator()) : null);
            if (value != null) values.add(value);
        }
        if (values.isEmpty()) return notApplicable("not_applicable");
        double average = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return new Result(round(average), null, null, "pass", light(spec, average));
    }

    private static Result yearEnd(Spec spec, List<Fact> facts) {
        // 年末快照取年度内最新周期，不累加也不平均
        Fact latest = facts.get(facts.size() - 1);
        Double value = latest.value() != null ? latest.value()
                : (latest.numerator() != null && latest.denominator() != null && latest.denominator() != 0
                    ? percentScale(spec, latest.numerator(), latest.denominator()) : null);
        if (value == null) return notApplicable("missing_input");
        return new Result(round(value), latest.numerator(), latest.denominator(), "pass", light(spec, value));
    }

    private static Result trend(Spec spec, List<Fact> facts) {
        if (facts.size() < 2) return new Result(null, null, null, "insufficient_periods", "na");
        Fact previous = facts.get(facts.size() - 2);
        Fact latest = facts.get(facts.size() - 1);
        Double previousValue = previous.value();
        Double latestValue = latest.value();
        if (previousValue == null || latestValue == null) return notApplicable("missing_input");
        double change = latestValue - previousValue;
        boolean high = "high".equals(spec.direction());
        boolean worsening = high ? change < 0 : change > 0;
        return new Result(round(latestValue), latest.numerator(), latest.denominator(),
                worsening ? "worsening" : "pass", light(spec, latestValue));
    }

    private static double percentScale(Spec spec, double numerator, double denominator) {
        double raw = denominator == 0 ? 0 : numerator / denominator;
        return "%".equals(spec.unit()) ? raw * 100 : raw;
    }

    /** 灯号：达标绿、预警线内黄、超阈值红；无值为 na。 */
    public static String light(Spec spec, Double value) {
        if (value == null) return "na";
        Double target = spec.target();
        Double warning = spec.warning();
        if (target == null) return "na";
        if ("high".equals(spec.direction())) {
            if (value >= target) return "green";
            if (warning != null && value >= warning) return "yellow";
            return "red";
        }
        if (value <= target) return "green";
        if (warning != null && value <= warning) return "yellow";
        return "red";
    }

    private static Result notApplicable(String reason) {
        return new Result(null, null, null, reason, "na");
    }

    private static double round(double value) { return Math.round(value * 10000d) / 10000d; }
}
