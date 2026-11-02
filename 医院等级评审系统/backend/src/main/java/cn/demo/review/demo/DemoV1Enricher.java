package cn.demo.review.demo;

import cn.demo.review.domain.Model.*;
import cn.demo.review.domain.Scoring;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.YearMonth;
import java.util.*;

/** 旧 v1 聚合的追加式场景升级；用户记录、已审核结论、历史快照原样保留。 */
public final class DemoV1Enricher {
    private DemoV1Enricher() {}

    public static void enrich(State state) {
        if (state.schemaVersion >= 2) return;
        Workspace w = state.workspace;
        for (Indicator indicator : w.indicators) {
            for (int m = 0; m < 12; m++) {
                String period = YearMonth.of(2024, 10).plusMonths(m).toString();
                if (indicator.trends.stream().anyMatch(t -> period.equals(t.period))) continue;
                double factor = "high".equals(indicator.direction) ? 0.94 + m * 0.004 : 1.10 - m * 0.005;
                double value = cn.demo.review.domain.SyntheticData.round(indicator.autoValue * factor);
                indicator.trends.add(new Trend(period, "%".equals(indicator.unit) ? Math.min(100, value) : value));
            }
            indicator.trends.sort(Comparator.comparing(t -> t.period));
        }
        for (int n = 0; n < 12; n++) {
            String id = "b-demo-history-" + n;
            if (w.batches.stream().anyMatch(b -> id.equals(b.id))) continue;
            YearMonth period = YearMonth.of(2025, 9).plusMonths(n);
            Batch b = new Batch(); b.id = id; b.period = period.toString(); b.status = "success"; b.batchKind = "auto";
            b.indicatorCount = w.indicators.size(); b.createdAt = period.plusMonths(1) + "-01T08:30:00+08:00";
            b.note = "历史合成月结：支持趋势、周期对比；不代表真实源系统采集"; w.batches.add(b);
        }
        for (int n = 0; n < 12; n++) {
            String id = "m-demo-history-" + n;
            if (w.manualValues.stream().anyMatch(m -> id.equals(m.id))) continue;
            Indicator i = w.indicators.get(n % w.indicators.size());
            Manual m = new Manual(); m.id = id; m.indicatorId = i.id; m.indicatorName = i.name; m.deptCode = "ALL";
            m.period = YearMonth.of(2026, 3).plusMonths(n % 6).toString(); m.value = i.value;
            m.status = n % 3 == 0 ? "approved" : n % 3 == 1 ? "rejected" : "submitted";
            m.submitter = "统计填报员（虚构）"; m.reviewer = "submitted".equals(m.status) ? "" : "质控复核员（虚构）";
            m.note = "合成场景：补充抽检记录并核对时间戳；自动事实与人工意见分别留痕";
            m.submittedAt = m.period + "-28T09:00:00+08:00"; w.manualValues.add(m);
        }
        String[] differences = {"主要诊断编码差异", "手术操作编码缺项", "出院日期差异", "费用归类差异", "入院病情差异", "住院天数差异"};
        for (int n = 0; n < 48; n++) {
            String id = "r-demo-" + n;
            if (w.reconciliations.stream().anyMatch(r -> id.equals(r.id))) continue;
            Reconcile r = new Reconcile(); r.id = id; r.inpatientNo = "DEMO****" + String.format("%05d", 21000 + n);
            r.dischargeDate = "2026-09-" + String.format("%02d", n % 28 + 1);
            r.deptName = w.departments.get(n % w.departments.size()).name; r.diffType = differences[n % differences.length];
            r.homeValue = n % 2 == 0 ? "合成编码 A" : "2026-09-15"; r.hqmsValue = n % 2 == 0 ? "合成编码 B" : "2026-09-16";
            r.status = n % 3 == 0 ? "explained" : "pending"; r.note = "合成病案核对；病案差异不计入条款抽查错误率"; w.reconciliations.add(r);
        }
        String[] stages = {"opened", "assigned", "doing", "evidence_ready", "verified", "closed"};
        for (int n = 0; n < 12; n++) {
            String id = "im-demo-scenario-" + n;
            if (w.improvements.stream().anyMatch(im -> id.equals(im.id))) continue;
            Indicator i = w.indicators.get(n % w.indicators.size());
            Improvement im = new Improvement(); im.id = id; im.indicatorId = i.id; im.indicatorName = i.name;
            im.origin = "red".equals(i.light) ? "red_light" : "worst_year"; im.status = stages[n % stages.length];
            im.owner = w.departments.get(n % w.departments.size()).name + "质控小组";
            im.dueDate = n % 4 == 0 ? "2026-10-20" : "2026-12-15";
            im.baseline = "high".equals(i.direction) ? i.value * 0.90 : i.value * 1.25;
            im.target = i.target; im.latestValue = i.value;
            im.actionText = "合成改进：岗位培训、专项抽查、病例复盘及按月效果跟踪";
            if ("closed".equals(im.status)) { im.closeMode = "improved"; im.closeNote = "2026-08 基线与 2026-09 同口径结果比较已改善，参见复核材料（合成）"; }
            w.improvements.add(im); state.improvementBaselinePeriods.put(id, "2026-08");
            if (n % 6 >= 3) attach(w, "improvement", id, i.name + "·改进效果复核（合成）");
        }
        for (int n = 0; n < Math.min(12, w.prerequisites.size()); n++) {
            Prereq p = w.prerequisites.get(n);
            if (!p.systemGenerated) attach(w, "prereq", p.id, p.title + "·演示自查佐证");
        }
        for (String title : List.of("月度质量安全分析简报（合成）", "数据质量与重放操作说明（演示）", "科室整改闭环工作指引（演示）")) {
            if (w.policies.stream().anyMatch(p -> title.equals(p.title))) continue;
            Policy p = new Policy(); p.id = "policy-demo-" + w.policies.size(); p.code = "LOCAL-DEMO-" + w.policies.size(); p.title = title;
            p.kind = "reference"; p.status = "registered"; p.publishedOn = "2026-01-01"; p.effectiveOn = "2026-02-01";
            p.impacts = List.of("indicator_def"); p.note = "自编合成演示材料，不作为官方政策依据"; w.policies.add(p);
        }
        ObjectMapper mapper = new ObjectMapper();
        for (int start = 2023; start <= 2025; start++) {
            String id = "score-demo-" + start;
            if (w.scoreRuns.stream().anyMatch(r -> id.equals(r.id))) continue;
            ScoreRun run = new ScoreRun(); run.id = id; run.cycleStart = start; run.cycleEnd = 2026; run.yearCount = 2027-start;
            run.createdAt = "2026-10-" + (10 + start - 2023) + "T10:00:00+08:00";
            for (Indicator i : w.indicators) {
                ScoreItem item = new ScoreItem(); item.indicatorId = i.id; item.name = i.name; item.unit = i.unit;
                List<Double> values = new ArrayList<>();
                for (Annual annual : i.annualValues) if (annual.year >= start) { item.yearValues.add(new Annual(annual.year, annual.value)); values.add(annual.value); }
                var r = Scoring.evaluate(values, i.direction, i.scoreType, i.target);
                item.mergedValue = r.mergedValue(); item.conform = r.conform(); item.reasonCode = r.reasonCode();
                if (item.conform != null) { run.includedCount++; if (item.conform) run.conformCount++; }
                run.items.add(item);
            }
            run.ratio = (double)run.conformCount / Math.max(1,run.includedCount);
            boolean nonCompliant = w.prerequisites.stream().anyMatch(p -> "non_compliant".equals(p.compliance));
            double errorRate = w.auditSamples.isEmpty() ? 0 : w.auditSamples.get(w.auditSamples.size()-1).errorRate;
            run.grade = Scoring.grade(run.conformCount,run.includedCount,nonCompliant,errorRate);
            run.warnings.add("演示试算：14/203 样例指标，2026 为完整年合成样本，不作为正式评审结论");
            run.warnings.add("前置复核未全部完成，须继续确认");
            if (nonCompliant) run.warnings.add("存在前置不符合，试算延期");
            if (errorRate > 0.1) run.warnings.add("最新条款抽查错误率严格超过10%，试算延期");
            ReportSnapshot snapshot = new ReportSnapshot(); snapshot.meta = mapper.convertValue(w.meta, Meta.class);
            snapshot.scoreRun = mapper.convertValue(run,ScoreRun.class);
            for (Prereq prereq:w.prerequisites) snapshot.prerequisites.add(mapper.convertValue(prereq,Prereq.class));
            for (AuditSample audit:w.auditSamples) snapshot.auditSamples.add(mapper.convertValue(audit,AuditSample.class));
            w.scoreRuns.add(run); state.reportSnapshots.put(id,snapshot);
        }
        state.schemaVersion = 2;
    }

    private static void attach(Workspace w, String type, String owner, String name) {
        String id = "file-demo-" + owner;
        if (w.evidenceFiles.stream().anyMatch(e -> id.equals(e.id))) return;
        Evidence e = new Evidence(); e.id = id; e.ownerType = type; e.ownerId = owner; e.fileName = name + ".txt";
        e.version = 1; e.createdAt = "2026-09-30T10:00:00+08:00"; e.size = evidenceContent(e).length; w.evidenceFiles.add(e);
        if ("prereq".equals(type)) w.prerequisites.stream().filter(p -> owner.equals(p.id)).forEach(p -> p.evidenceCount++);
        else w.improvements.stream().filter(im -> owner.equals(im.id)).forEach(im -> im.evidenceCount++);
    }

    private static byte[] evidenceContent(Evidence e) {
        return (e.fileName + "\n演示环境 / 合成数据\n归属：" + e.ownerType + "/" + e.ownerId
                + "\n自查：流程检查、记录核对、异常定位。\n措施：培训、复核、按月跟踪。\n"
                + "结论：仅作为合成演示佐证，效果判定须结合对应周期指标。\n复核：科室质控小组（虚构）。\n").getBytes(StandardCharsets.UTF_8);
    }

    public static void writeMissingEvidence(State state, Path uploads) throws IOException {
        for (Evidence e : state.workspace.evidenceFiles) {
            if (e.id == null || !e.id.startsWith("file-demo-") || !e.id.matches("[A-Za-z0-9-]+")) continue;
            Path target = uploads.resolve(e.id).normalize();
            if (!target.startsWith(uploads) || Files.exists(target)) continue;
            Files.write(target,evidenceContent(e),StandardOpenOption.CREATE_NEW);
        }
    }
}
