package cn.demo.review.demo;

import java.time.YearMonth;
import java.util.*;

/**
 * 组会演示用的确定性场景目录。这里只生成规则和数值，不直接写数据库。
 * 所有值均为合成值；正式服务仍负责质量校验、公式计算、权限和审计。
 */
public final class DemoScenarioCatalog {
    public static final String SCENARIO_CODE = "group-demo-2026";
    public static final int SCENARIO_VERSION = 2;
    public static final long SEED = 20261031L;

    private static final List<YearMonth> PERIODS = periodsOf(YearMonth.of(2025, 10), 12);
    private static final List<String> METRICS = List.of(
            "i01", "i02", "i03", "i04", "i05", "i06", "i07",
            "i08", "i09", "i10", "i11", "i12", "i13", "i14");
    private static final List<String> DEPARTMENTS = List.of("CARD", "SURG", "NEUR", "ORTH", "RESP", "GYNE");
    private static final Set<String> SCENARIO_KINDS = Set.of(
            "baseline", "worsening", "jump", "missing", "quality_gate", "recovery");

    public String scenarioCode() { return SCENARIO_CODE; }
    public int scenarioVersion() { return SCENARIO_VERSION; }
    public long seed() { return SEED; }
    public List<YearMonth> periods() { return PERIODS; }
    public List<String> metricCodes() { return METRICS; }
    public List<String> departmentCodes() { return DEPARTMENTS; }
    public Set<String> scenarioKinds() { return SCENARIO_KINDS; }

    public String scenarioKind(String metricCode, String deptCode, YearMonth period) {
        if ("i12".equals(metricCode) && !"ALL".equals(deptCode)) return "missing";
        if ("i01".equals(metricCode) && "SURG".equals(deptCode)
                && !period.isBefore(YearMonth.of(2026, 4))) return "worsening";
        if ("i11".equals(metricCode)
                && YearMonth.of(2026, 9).equals(period)) return "jump";
        if ("i03".equals(metricCode) && "CARD".equals(deptCode)
                && Set.of(YearMonth.of(2025, 12), YearMonth.of(2026, 3)).contains(period)) return "quality_gate";
        if ("i05".equals(metricCode) && "CARD".equals(deptCode)
                && !period.isBefore(YearMonth.of(2026, 6))) return "recovery";
        return "baseline";
    }

    public double value(String metricCode, String deptCode, YearMonth period) {
        int monthIndex = Math.max(0, (period.getYear() - 2025) * 12 + period.getMonthValue() - 10);
        double base = switch (metricCode) {
            case "i01" -> 0.18;
            case "i02" -> 38.0;
            case "i03" -> 0.25;
            case "i04" -> 1.40;
            case "i05" -> 1.25;
            case "i06" -> 91.0;
            case "i07" -> 96.0;
            case "i08" -> 92.0;
            case "i09" -> 97.0;
            case "i10" -> 88.0;
            case "i11" -> 1.18;
            case "i12" -> 850.0;
            case "i13" -> 1.85;
            case "i14" -> 7.20;
            default -> throw new IllegalArgumentException("未知演示指标：" + metricCode);
        };
        String kind = scenarioKind(metricCode, deptCode, period);
        double trend = switch (kind) {
            case "worsening" -> (monthIndex - 5) * 0.08;
            case "jump" -> 1.12;
            case "recovery" -> -(monthIndex - 7) * 0.17;
            case "missing", "quality_gate" -> 0;
            default -> metricCode.equals("i08") ? monthIndex * 0.35 : monthIndex * 0.002;
        };
        if ("i12".equals(metricCode)) return 820 + monthIndex * 3;
        double deptOffset = Math.floorMod(Objects.hash(SEED, metricCode, deptCode), 7)
                * (Set.of("i02", "i06", "i07", "i08", "i09", "i10").contains(metricCode) ? 0.25 : 0.01);
        return round(base + trend + deptOffset);
    }

    public DemoScenarioStats minimumStats() {
        // 验收下限，不把理论笛卡尔积冒充已落库事实数；实际统计由 JDBC 计数。
        int facts = 500;
        return new DemoScenarioStats(1, DEPARTMENTS.size(), METRICS.size(), PERIODS.size(),
                PERIODS.size(), 6, facts, facts, 5, 20, 8, 12, 3);
    }

    private static List<YearMonth> periodsOf(YearMonth start, int count) {
        List<YearMonth> result = new ArrayList<>();
        for (int i = 0; i < count; i++) result.add(start.plusMonths(i));
        return List.copyOf(result);
    }

    private static double round(double value) {
        return Math.round(value * 10000d) / 10000d;
    }
}
