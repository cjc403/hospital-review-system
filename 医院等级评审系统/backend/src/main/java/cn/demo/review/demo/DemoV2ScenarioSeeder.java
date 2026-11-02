package cn.demo.review.demo;

import cn.demo.review.v2.assessment.AssessmentService;
import cn.demo.review.v2.evidence.EvidenceService;
import cn.demo.review.v2.platform.ReviewProfile;
import cn.demo.review.v2.quality.QualityService;
import cn.demo.review.v2.score.ScoreService;
import cn.demo.review.v2.security.AuthContext;
import cn.demo.review.v2.task.TaskService;
import cn.demo.review.v3.alert.AlertService;
import cn.demo.review.v3.report.ReportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.util.*;

/** 通过业务服务编排任务、证据、整改和完整报告，不绕过质量门或状态机。 */
@Component
@Order(40)
public class DemoV2ScenarioSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoV2ScenarioSeeder.class);
    private static final String HOSPITAL = "H001";
    private static final String SCENARIO = "group-demo-2026-v2";
    private static final int VERSION = 2;
    private static final String ACTOR = "demo-v2-seed-v2";
    private final ReviewProfile profile;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final QualityService quality;
    private final ScoreService scores;
    private final TaskService tasks;
    private final AssessmentService assessment;
    private final EvidenceService evidence;
    private final AlertService alerts;
    private final ReportService reports;
    private final boolean enabled;
    private final DemoScenarioCatalog catalog = new DemoScenarioCatalog();

    public DemoV2ScenarioSeeder(ReviewProfile profile, JdbcTemplate jdbc, ObjectMapper json,
            QualityService quality, ScoreService scores, TaskService tasks, AssessmentService assessment,
            EvidenceService evidence, AlertService alerts, ReportService reports,
            @Value("${demo.rich-seed.enabled:true}") boolean enabled) {
        this.profile = profile; this.jdbc = jdbc; this.json = json; this.quality = quality;
        this.scores = scores; this.tasks = tasks; this.assessment = assessment;
        this.evidence = evidence; this.alerts = alerts; this.reports = reports; this.enabled = enabled;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws Exception {
        if (!profile.isDemo() || !enabled || count("SELECT COUNT(*) FROM demo_seed_marker WHERE scenario_code=? AND scenario_version=?", SCENARIO, VERSION) > 0) return;
        List<String> published = jdbc.query("SELECT id FROM standard_version WHERE status='published' ORDER BY created_at DESC",
                (rs, i) -> rs.getString(1));
        if (published.isEmpty()) return;
        String version = published.get(0);
        AuthContext ctx = new AuthContext(ACTOR, HOSPITAL, null, Set.of("review_office"), "demo");
        seedTasksAndMaterials(ctx, version);
        seedBatches(ctx, version);
        // 首份快照形成于本轮抽查/前置结论之前，以便展示后续变更不会改写历史。
        createScore(ctx, version, 2025, 2025, "baseline");
        seedAssessment(ctx, version);
        createScore(ctx, version, 2025, 2026, "two-year");
        createScore(ctx, version, 2026, 2026, "current");
        createScore(ctx, version, 2024, 2026, "missing-year");
        seedImprovements(ctx);
        for (String[] range : new String[][]{{"2026-07","2026-07"},{"2026-08","2026-09"},{"2025-10","2026-09"}}) {
            ObjectNode body = json.createObjectNode().put("versionId", version).put("periodFrom", range[0]).put("periodTo", range[1]);
            reports.create(ctx, body, "demo-report-" + range[0]);
        }
        Map<String,Object> stats = new LinkedHashMap<>();
        for (String table : List.of("review_task", "evidence_item", "collection_batch", "score_run", "audit_sample", "v3_improvement", "v3_report"))
            stats.put(table, count("SELECT COUNT(*) FROM " + table + " WHERE hospital_id=?", HOSPITAL));
        jdbc.update("INSERT INTO demo_seed_marker (scenario_code, scenario_version, seed, summary_json, created_at) VALUES (?,?,?,?,?)",
                SCENARIO, VERSION, DemoScenarioCatalog.SEED, json.writeValueAsString(stats), now());
        log.info("Rich demo workflows completed: {}", stats);
    }

    private void seedTasksAndMaterials(AuthContext ctx, String version) throws Exception {
        List<String> clauses = jdbc.query("SELECT code FROM standard_clause WHERE version_id=? ORDER BY code",
                (rs,i) -> rs.getString(1), version);
        int index = 0;
        for (String dept : catalog.departmentCodes()) {
            ObjectNode body = json.createObjectNode().put("versionId",version).put("deptCode",dept)
                    .put("requestKey", SCENARIO+"-v2-"+dept).put("assigneePost",dept+" 科室质控员")
                    .put("priority", Set.of("CARD","SURG").contains(dept)?"high":"normal")
                    .put("dueDate", Set.of("SURG","ORTH").contains(dept)?"2026-10-20":"2026-12-15");
            var codes = body.putArray("clauseCodes");
            clauses.forEach(codes::add);
            @SuppressWarnings("unchecked")
            List<Map<String,Object>> created = (List<Map<String,Object>>) tasks.assign(ctx,body,"demo-tasks-"+dept).get("tasks");
            for (Map<String,Object> task : created) {
                String id = String.valueOf(task.get("id"));
                int stage = index++ % 6;
                // 材料覆盖所有科室，每科室前两项各有两个可下载版本。
                if (index % 10 == 1 || index % 10 == 2) {
                    String title = dept+" "+task.get("title")+"·月度自查材料（合成）";
                    String itemId = upload(ctx,"task",id,title,1);
                    if (index % 10 == 1) upload(ctx,"task",id,title,2);
                    if (stage == 1 || stage == 4) evidence.review(ctx,itemId,"approved","合成检查记录完整，允许用于演示复核","demo-evidence-review");
                    else if (stage == 2) evidence.review(ctx,itemId,"rejected","缺少复核签署页，退回补充（合成场景）","demo-evidence-review");
                }
                if (stage == 0) continue;
                if (stage == 5) { taskTransition(ctx,id,"cancelled","演示任务合并，历史保留"); continue; }
                taskTransition(ctx,id,"in_progress","");
                if (stage >= 2) taskTransition(ctx,id,"submitted","");
                if (stage == 3) taskTransition(ctx,id,"returned","缺少合成抽查记录的复核页");
                if (stage == 4) taskTransition(ctx,id,"accepted","制度与抽查材料一致，验收通过（合成）");
            }
        }
    }

    private void taskTransition(AuthContext ctx,String id,String status,String comment) {
        tasks.update(ctx,id,json.createObjectNode().put("status",status).put("comment",comment),"demo-task-"+status);
    }

    private String upload(AuthContext ctx,String type,String ownerId,String title,int version) throws Exception {
        var file = new DemoEvidenceFile(title, version);
        Map<String,Object> response = evidence.upload(ctx,file,type,ownerId,title,"合成自查及复核记录","2027-12-31","demo-proof");
        return String.valueOf(((Map<?,?>)response.get("item")).get("id"));
    }

    private void seedBatches(AuthContext ctx,String version) {
        List<Map<String,Object>> defs = jdbc.query("SELECT code,unit FROM indicator_definition WHERE version_id=? ORDER BY code",
                (rs,i)->Map.of("code",rs.getString(1),"unit",rs.getString(2)), version);
        for (YearMonth period : catalog.periods()) {
            // 按指标补缺，不覆盖用户原有已应用批次，也不因一条旧批次而跳过全月。
            ObjectNode body=json.createObjectNode().put("sourceCode","SYNTHETIC-DEMO").put("period",period.toString());
            var rows=body.putArray("rows");
            for (var def:defs) {
                String code=String.valueOf(def.get("code"));
                if (count("SELECT COUNT(*) FROM metric_fact f JOIN collection_batch b ON b.id=f.batch_id WHERE b.hospital_id=? AND b.status='applied' AND f.period_code=? AND f.indicator_code=?",HOSPITAL,period.toString(),code)>0) continue;
                String unit=String.valueOf(def.get("unit"));
                double value=catalog.value(code,"ALL",period);
                var row=rows.addObject().put("indicatorCode",code).put("unit",unit).put("value",value);
                if ("%".equals(unit)) row.put("numerator",Math.round(value*100)).put("denominator",10000d);
                if ("i11".equals(code)) row.put("numerator",value*1000).put("denominator",1000d);
            }
            if (rows.isEmpty()) continue;
            var result=quality.createBatch(ctx,body,"demo-v2-batch-"+period);
            var batch=(Map<?,?>)result.get("batch");
            if (!"validated".equals(batch.get("status"))) throw new IllegalStateException("演示批次不合格："+result.get("issues"));
            quality.apply(ctx,String.valueOf(batch.get("id")),"demo-v2-apply-"+period);
        }
    }

    private void seedAssessment(AuthContext ctx,String version) {
        for (String period:List.of("2025-10","2025-12","2026-02","2026-04","2026-06","2026-09"))
            assessment.createSample(ctx,json.createObjectNode().put("period",period),"demo-audit-"+period);
        List<String> codes=jdbc.query("SELECT code FROM prereq_definition WHERE version_id=? AND system_generated=0 ORDER BY code LIMIT 12",
                (rs,i)->rs.getString(1),version);
        for (int i=0;i<codes.size();i++) {
            if (count("SELECT COUNT(*) FROM prereq_conclusion WHERE hospital_id=? AND version_id=? AND code=?",HOSPITAL,version,codes.get(i))>0) continue;
            boolean missing=i==5;
            var body=json.createObjectNode().put("versionId",version)
                    .put("compliance",i==2?"non_compliant":missing?"unknown":"compliant")
                    .put("workflow",missing?"draft":"accepted")
                    .put("statement",i==2?"合成抽查发现夜间交接记录缺项，已立项整改":"合成自查材料与复核记录一致；仅用于组会演示");
            assessment.setConclusion(ctx,codes.get(i),body,"demo-prereq-"+codes.get(i));
            uploadUnchecked(ctx,"prereq",codes.get(i),"前置条款 "+codes.get(i)+"·自查佐证（合成）");
        }
    }

    private void uploadUnchecked(AuthContext ctx,String type,String owner,String title) {
        try { upload(ctx,type,owner,title,1); }
        catch (Exception e) { throw new IllegalStateException("演示附件生成失败",e); }
    }

    private void createScore(AuthContext ctx,String version,int start,int end,String name) {
        scores.create(ctx,json.createObjectNode().put("versionId",version).put("cycleStart",start).put("cycleEnd",end),"demo-score-"+name);
    }

    private void seedImprovements(AuthContext ctx) throws Exception {
        // 三个来源月份各有“证据齐备关闭”和“持续跟踪”的案例。
        String[][] themes={
                {"i05","CARD","2026-07","院感手卫生依从性波动","加强床旁巡检并复核感控记录"},
                {"i05","CARD","2026-08","感控培训覆盖不足","增加薄弱班次专项培训"},
                {"i05","CARD","2026-09","干预效果持续跟踪","按月复核院感趋势，巩固改善"},
                {"i01","SURG","2026-07","围手术期风险评估不充分","病例复盘与术前多学科讨论"},
                {"i03","ORTH","2026-08","非计划再次手术原因待核实","补充手术安全核查和病例讨论"},
                {"i07","RESP","2026-09","危急值处置记录延迟","优化闭环提醒与夜班交接"},
                {"i06","ORTH","2026-09","VTE风险评估依从性不足","术前风险评估与护理复核"},
                {"i08","CARD","2026-09","核心制度抽检仍有缺项","按周抽检并汇总科室改进记录"}
        };
        for (int i=0;i<themes.length;i++) {
            String[] t=themes[i];
            List<String> resultIds=jdbc.query("SELECT id FROM metric_result WHERE hospital_id=? AND metric_code=? AND dept_code=? AND period_code=?",
                    (rs,n)->rs.getString(1),HOSPITAL,t[0],t[1],t[2]);
            if (resultIds.isEmpty()) throw new IllegalStateException("演示整改缺少来源结果："+Arrays.toString(t));
            ObjectNode body=json.createObjectNode().put("sourceType","result").put("sourceId",resultIds.get(0))
                    .put("rootCause",t[3]).put("action",t[4]).put("owner",t[1]+" 质控小组").put("dueDate",i<3?"2026-10-25":"2026-12-15");
            String baselinePeriod=java.time.YearMonth.parse(t[2]).minusMonths(1).toString();
            List<Double> baselineValues=jdbc.query("SELECT metric_value FROM metric_result WHERE hospital_id=? AND metric_code=? AND dept_code=? AND period_code=?",
                    (rs,n)->rs.getDouble(1),HOSPITAL,t[0],t[1],baselinePeriod);
            if (!baselineValues.isEmpty()) body.put("baselineValue",baselineValues.get(0));
            body.put("rootCause",t[3]+"；同口径对照："+baselinePeriod+" → "+t[2]+"（合成）");
            Map<String,Object> improvement=alerts.createFromSource(ctx,body,"demo-improvement-"+i);
            String id=String.valueOf(improvement.get("id"));
            if (i<3) {
                String itemId=upload(ctx,"improvement",id,t[2]+" 院感专项改进复核记录（合成）",1);
                upload(ctx,"improvement",id,t[2]+" 院感专项改进复核记录（合成）",2);
                evidence.review(ctx,itemId,"approved","合成佐证齐备；改善结论仅供演示","demo-proof-approved");
                alerts.reviewImprovement(ctx,id,json.createObjectNode().put("conclusion",i==2?"improved":"partially_improved")
                        .put("note","参见后续周期趋势、同口径对照与附件；合成质量改进案例").put("evidenceItemId",itemId),"demo-close-"+i);
            } else if (i==3) upload(ctx,"improvement",id,"围手术期复盘·待复核材料（合成）",1);
        }
        List<String> ids=jdbc.query("SELECT id FROM v3_alert WHERE hospital_id=? AND period_code>='2026-07' AND status='open' ORDER BY period_code DESC,id LIMIT 6",
                (rs,n)->rs.getString(1),HOSPITAL);
        for (int i=0;i<ids.size();i++) {
            String id=ids.get(i);
            if (i==0) { alerts.transition(ctx,id,"ignore",json.createObjectNode().put("note","已核实为演示非适用范围，保留说明"),"demo-ignore"); continue; }
            alerts.transition(ctx,id,"ack",json.createObjectNode().put("note","科室已收到演示预警"),"demo-ack");
            if (i>1) alerts.transition(ctx,id,"assign",json.createObjectNode().put("assignee","科室质控小组")
                    .put("dueDate","2026-12-15").put("note","纳入月度复盘"),"demo-assign");
            if (i>3) alerts.transition(ctx,id,"resolve",json.createObjectNode().put("note","数据问题已核实并复核，临床改善另按整改证据判断"),"demo-resolve");
        }
    }

    private int count(String sql,Object... args) {
        Integer value=jdbc.queryForObject(sql,Integer.class,args);
        return value==null?0:value;
    }

    private static String now() { return java.time.OffsetDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).toString(); }
}
