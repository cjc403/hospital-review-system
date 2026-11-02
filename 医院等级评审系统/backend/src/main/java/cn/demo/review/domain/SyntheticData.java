package cn.demo.review.domain;

import java.time.YearMonth;
import java.util.*;
import static cn.demo.review.domain.Model.*;

/**
 * 固定、完全合成的演示事实。指标口径来自 {@link StandardPack}，本类只负责演示数值与演示业务状态。
 * 改指标不再需要改本类：在 standards/demo-standard-v1.json 增加一条即可。
 */
public final class SyntheticData {
    private SyntheticData() {}

    private static final StandardPack PACK = StandardPack.load();
    public static final Set<String> PATIENT_INDICATORS = patientIndicators();
    private static final String DEMO_DEFINITION_NOTE =
            " 本指标目标与告警线仅为演示目标，非官方阈值；全部事实为合成样本，不接真实数据。";

    private static Set<String> patientIndicators() {
        Set<String> codes = new LinkedHashSet<>();
        for (IndicatorDefinition d : PACK.indicators) if (d.demoDenominator != null) codes.add(d.code);
        return Collections.unmodifiableSet(codes);
    }

    public static StandardPack pack() { return PACK; }

    public static State initial() {
        State s = new State();
        Workspace w = s.workspace;
        w.departments.addAll(List.of(
                new Department("CARD", "心血管内科", "陈明（演示）"),
                new Department("SURG", "普通外科", "林晓（演示）"),
                new Department("NEUR", "神经内科", "张宁（演示）"),
                new Department("ORTH", "骨科", "刘芳（演示）"),
                new Department("RESP", "呼吸内科", "王磊（演示）"),
                new Department("GYNE", "妇产科", "赵悦（演示）")));
        for (int n = 0; n < PACK.indicators.size(); n++) {
            IndicatorDefinition def = PACK.indicators.get(n);
            Indicator i = new Indicator();
            i.id = def.code; i.code = "DEMO-" + def.code.substring(1);
            i.name = def.name; i.chapter = chapterTitle(def.chapterCode);
            i.dimension = def.dimension; i.owner = n < 7 ? "医务部" : "质量管理办公室";
            i.unit = def.unit; i.direction = def.direction; i.scoreType = def.scoreType;
            i.annualAgg = def.annualAgg; i.collectMode = def.collectMode;
            i.target = def.target; i.warn = def.warn;
            i.numeratorText = def.numeratorRule;
            i.denominatorText = def.denominatorRule;
            i.includeText = def.includeRule + DEMO_DEFINITION_NOTE;
            i.excludeText = def.excludeRule;
            i.sourceTables = def.demoDenominator != null
                    ? List.of("visit_discharge", "demo_case_fact") : List.of("demo_summary_fact");
            i.excludedCount = 3 + n; i.batchId = "b-initial"; i.source = "auto";
            for (int y = 2023; y <= 2026; y++) {
                double annual = annualValue(def, y);
                i.annualValues.add(new Annual(y, def.isPercent() ? Math.min(100, annual) : annual));
            }
            for (int m = 0; m < 12; m++) {
                YearMonth p = YearMonth.of(2025, 10).plusMonths(m);
                double v = round(monthlyValue(def, p));
                i.trends.add(new Trend(p.toString(), def.isPercent() ? Math.min(100, v) : v));
            }
            fillFacts(i, w.departments, "2026-09");
            w.indicators.add(i);
        }
        Batch b = new Batch();
        b.id = "b-initial"; b.period = "2026-09"; b.status = "success"; b.batchKind = "auto";
        b.indicatorCount = PACK.indicators.size(); b.createdAt = "2026-10-01T08:30:00+08:00";
        b.note = "初始合成数据月结；非真实医院数据";
        w.batches.add(b);
        Manual m = new Manual();
        m.id = "m-initial"; m.indicatorId = "i08"; m.indicatorName = name("i08");
        m.deptCode = "ALL"; m.period = "2026-09"; m.value = 96;
        m.note = "合成核心制度检查补录，待质控审核"; m.status = "submitted";
        m.submitter = "演示填报员"; m.submittedAt = "2026-10-02T09:00:00+08:00";
        w.manualValues.add(m);
        String[] differences = {"主要诊断编码不一致", "出院时间不一致", "手术编码缺失", "费用分类差异", "入院病情差异", "住院天数差异"};
        for (int n = 0; n < 12; n++) {
            Reconcile r = new Reconcile();
            r.id = "r%02d".formatted(n + 1);
            r.inpatientNo = "DEMO****%04d".formatted(1001 + n);
            r.dischargeDate = "2026-09-%02d".formatted(10 + n);
            r.deptName = w.departments.get(n % 6).name;
            r.diffType = differences[n % 6];
            r.homeValue = n % 2 == 0 ? "示例A" : "2026-09-15";
            r.hqmsValue = n % 2 == 0 ? "示例B" : "2026-09-16";
            r.status = "pending"; r.note = "病案差异不参与条款抽查错误率";
            w.reconciliations.add(r);
        }
        List<StandardPack.PrerequisiteDef> defs = PACK.prerequisites;
        for (int n = 0; n < defs.size(); n++) {
            StandardPack.PrerequisiteDef def = defs.get(n);
            Prereq p = new Prereq();
            p.id = def.systemGenerated ? "p30" : "p%02d".formatted(n + 1);
            p.code = def.code; p.section = def.section;
            p.title = def.title + (def.systemGenerated ? "" : "（演示摘要）");
            p.systemGenerated = def.systemGenerated;
            boolean unknown = def.systemGenerated || n == 25 || n == 26;
            p.compliance = unknown ? "unknown" : "compliant";
            p.workflow = unknown ? "draft" : "accepted";
            p.owner = n < 16 ? "医务部" : "质量管理办公室";
            p.statement = def.systemGenerated ? "尚未发起条款抽查"
                    : unknown ? "尚待演示复核" : "基于合成资料的演示结论；不代表真实法律认定";
            w.prerequisites.add(p);
        }
        for (int n = 0; n < 2; n++) {
            Improvement im = new Improvement();
            im.id = "im-initial-" + (n + 1);
            Indicator i = w.indicators.get(n == 0 ? 3 : 12);
            im.indicatorId = i.id; im.indicatorName = i.name;
            im.origin = n == 0 ? "red_light" : "worst_year";
            im.status = n == 0 ? "opened" : "doing";
            im.owner = n == 0 ? "普通外科" : "护理部";
            im.dueDate = "2026-11-30"; im.baseline = i.value; im.target = i.target; im.latestValue = i.value;
            im.actionText = n == 0 ? "" : "排班与岗位能力提升（合成示例）";
            w.improvements.add(im);
            s.improvementBaselinePeriods.put(im.id, "2026-09");
        }
        String[] policyTitles = {"三级医院评审标准（2025年版）", "国家三级公立医院绩效监测操作手册（2025版）", "院内演示指标口径说明",
                "医院质量安全月度分析简报（合成）", "科室整改闭环管理办法（演示）", "数据质量门与批次重放说明（演示）"};
        for (int n = 0; n < policyTitles.length; n++) {
            Policy p = new Policy();
            p.id = "policy-" + (n + 1); p.code = "POL-DEMO-" + (n + 1); p.title = policyTitles[n];
            p.kind = n == 2 ? "indicator_pack" : "national_standard";
            p.status = n == 2 ? "registered" : "confirmed";
            p.publishedOn = "2025-06-10"; p.effectiveOn = "2025-07-01";
            p.impacts = List.of(n == 2 ? "indicator_def" : "clause");
            p.note = "本轮仅登记元数据；不自动解析或改写政策";
            w.policies.add(p);
        }
        cn.demo.review.demo.DemoV1Enricher.enrich(s);
        return s;
    }

    private static double annualValue(IndicatorDefinition def, int year) {
        int index = year - 2023;
        if (!def.demoAnnual.isEmpty()) {
            if (index >= def.demoAnnual.size()) return def.demoAnnual.get(def.demoAnnual.size() - 1);
            return def.demoAnnual.get(index);
        }
        double factor = def.isLowBetter() ? 0.76 + 0.08 * index : 1.05 - 0.016 * index;
        return round(def.demoCurrent * factor);
    }

    private static String chapterTitle(String code) {
        for (StandardPack.Chapter c : PACK.chapters) if (c.code.equals(code)) return c.title;
        return code;
    }

    private static String name(String code) {
        IndicatorDefinition def = PACK.indicator(code);
        return def == null ? code : def.name;
    }

    public static void fillFacts(Indicator i, List<Department> departments, String period) {
        IndicatorDefinition def = PACK.indicator(i.id);
        if (def == null || def.demoCurrent == null) throw new IllegalStateException("缺少演示口径定义：" + i.id);
        double desired = monthlyValue(def, YearMonth.parse(period));
        i.departmentValues.clear();
        double numerator = 0, denominator = 0;
        for (int d = 0; d < departments.size(); d++) {
            Department dep = departments.get(d);
            DepartmentValue v = new DepartmentValue();
            v.code = dep.code; v.name = dep.name;
            if (PATIENT_INDICATORS.contains(i.id)) {
                int denom = def.demoDenominator;
                int count = (int) Math.round(denom * desired / 100d) + (d == 0 ? 1 : d == 1 ? -1 : 0);
                count = Math.max(0, Math.min(denom, count));
                v.denominator = (double) denom; v.numerator = (double) count;
                v.value = round(100d * count / denom);
                numerator += count; denominator += denom;
            } else {
                v.numerator = null; v.denominator = null;
                v.value = round(desired * (0.95 + d * 0.02));
            }
            v.light = light(i, v.value);
            i.departmentValues.add(v);
        }
        if (PATIENT_INDICATORS.contains(i.id)) {
            i.numerator = numerator; i.denominator = denominator;
            i.autoValue = round(100d * numerator / denominator);
        } else {
            i.numerator = null; i.denominator = null; i.autoValue = round(desired);
        }
        i.value = i.autoValue; i.source = "auto"; i.light = currentLight(i, period);
    }

    private static double monthlyValue(IndicatorDefinition def, YearMonth period) {
        if (period.isAfter(YearMonth.of(2026,9))) return def.demoCurrent*(def.isLowBetter()?0.5:1.05);
        int month=(int)Math.max(0,Math.min(11,java.time.temporal.ChronoUnit.MONTHS.between(YearMonth.of(2025,10),period)));
        double factor;
        if (Set.of("i05","i06","i08").contains(def.code)) {
            // 三条改善后稳定的序列，最后三个月保持本期值。
            double remaining=Math.max(0,8-month)/8d;
            factor=def.isLowBetter()?1+0.5*remaining:1-0.12*remaining;
        } else if (Set.of("i11","i14").contains(def.code)) {
            factor=month==11?1:0.64+month*0.004;
        } else factor=def.isLowBetter()?0.78+month*0.02:1.066-month*0.006;
        return def.demoCurrent*factor;
    }

    public static String currentLight(Indicator i, String period) {
        String threshold = light(i, i.value);
        if (threshold.equals("na")) return threshold;
        var previous = i.trends.stream().filter(t -> t.period.compareTo(period) < 0)
                .sorted(Comparator.comparing(t -> t.period)).toList();
        if (previous.size() < 2) return threshold;
        Trend one = previous.get(previous.size() - 2), two = previous.get(previous.size() - 1);
        YearMonth current = YearMonth.parse(period);
        if (!one.period.equals(current.minusMonths(2).toString())
                || !two.period.equals(current.minusMonths(1).toString())
                || one.value == null || two.value == null) return threshold;
        boolean worsening = i.direction.equals("high")
                ? two.value < one.value && i.value < two.value
                : two.value > one.value && i.value > two.value;
        return worsening ? "red" : threshold;
    }

    public static String light(Indicator i, Double value) {
        if (value == null || !Double.isFinite(value)) return "na";
        return i.direction.equals("high")
                ? (value >= i.target ? "green" : value >= i.warn ? "yellow" : "red")
                : (value <= i.target ? "green" : value <= i.warn ? "yellow" : "red");
    }

    public static double round(double value) { return Math.round(value * 10000d) / 10000d; }
}
