package cn.demo.review.demo;

import cn.demo.review.v2.platform.ReviewProfile;
import cn.demo.review.v2.security.AuthContext;
import cn.demo.review.v3.ingestion.IngestionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.time.YearMonth;
import java.util.*;

/**
 * 跨周期 v3 组会演示场景。只在 demo profile 执行，使用正式接入服务写入事实、结果、预警和血缘。
 */
@Component
@Order(30)
public class DemoScenarioSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoScenarioSeeder.class);
    private static final String HOSPITAL = "H001";
    private static final String SOURCE = "SYNTHETIC-DEMO";

    private final ReviewProfile profile;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final IngestionService ingestion;
    private final boolean richSeedEnabled;
    private final DemoScenarioCatalog catalog = new DemoScenarioCatalog();

    public DemoScenarioSeeder(ReviewProfile profile, JdbcTemplate jdbc, ObjectMapper json,
                              IngestionService ingestion,
                              @Value("${demo.rich-seed.enabled:true}") boolean richSeedEnabled) {
        this.profile = profile;
        this.jdbc = jdbc;
        this.json = json;
        this.ingestion = ingestion;
        this.richSeedEnabled = richSeedEnabled;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void run(ApplicationArguments args) {
        if (!profile.isDemo()) {
            log.info("prod profile：跳过丰富演示场景");
            return;
        }
        if (!richSeedEnabled) {
            log.info("demo.rich-seed.enabled=false，跳过丰富演示场景");
            return;
        }
        if (marked()) {
            log.info("demo scenario already seeded: {} v{}", catalog.scenarioCode(), catalog.scenarioVersion());
            return;
        }
        String versionId = latestVersion();
        if (versionId == null) {
            log.warn("丰富演示场景跳过：尚无已发布标准版本");
            return;
        }
        AuthContext system = new AuthContext("demo-scenario-seed", HOSPITAL, null,
                Set.of("review_office"), "demo");
        if (((List<?>) ingestion.mappings(system, SOURCE).get("items")).isEmpty()) {
            ObjectNode mapping = json.createObjectNode().put("sourceCode", SOURCE);
            ObjectNode fields = mapping.putObject("fields");
            fields.put("metricCode", "code").put("deptCode", "dept").put("numerator", "num")
                    .put("denominator", "den").put("value", "val").put("unit", "unit");
            mapping.putObject("codeMap");
            ingestion.saveMapping(system, mapping, "demo-scenario-mapping");
        }
        int applied = seedApplied(system, versionId);
        int rejected = seedRejected(system);
        int marker = mark(applied, rejected);
        log.info("seeded rich demo scenario code={} applied={} rejected={} marker={}",
                catalog.scenarioCode(), applied, rejected, marker);
    }

    private int seedApplied(AuthContext system, String versionId) {
        int created = 0;
        for (YearMonth period : catalog.periods()) {
            String periodCode = period.toString();
            ObjectNode body = batchBody(versionId, period);
            if (body.path("rows").isEmpty()) continue;
            Map<String, Object> detail = ingestion.createBatch(system, body, "demo-scenario-" + periodCode);
            Map<?, ?> batch = (Map<?, ?>) detail.get("batch");
            if (!"validated".equals(String.valueOf(batch.get("status")))) {
                log.warn("rich demo batch rejected unexpectedly period={} issues={}", periodCode, detail.get("issues"));
                throw new IllegalStateException("演示批次意外阻断：" + periodCode + detail.get("issues"));
            }
            ingestion.apply(system, String.valueOf(batch.get("id")), "demo-scenario-apply-" + periodCode);
            created++;
        }
        return created;
    }

    private int seedRejected(AuthContext system) {
        int created = 0;
        created += rejected(system, "2027-03", row("i99", "CARD", null, null, 1d, "%"));
        created += rejected(system, "2027-04", row("i01", "CARD", 1d, 0d, null, "%"));
        created += rejected(system, "2027-05", row("i01", "UNKNOWN", 1d, 100d, null, "%"));
        ObjectNode duplicate = json.createObjectNode();
        duplicate.putArray("rows").add(row("i01", "CARD", 1d, 100d, null, "%"))
                .add(row("i01", "CARD", 2d, 100d, null, "%"));
        created += rejectedBody(system, "2027-06", duplicate);
        created += rejected(system, "2027-07", row("i01", "CARD", 1d, 100d, null, "张"));
        created += rejected(system, "2026-09", row("i01", "CARD", 1d, 100d, null, "%"));
        return created;
    }

    private int rejected(AuthContext system, String period, ObjectNode row) {
        ObjectNode body = json.createObjectNode();
        body.putArray("rows").add(row);
        return rejectedBody(system, period, body);
    }

    private int rejectedBody(AuthContext system, String period, ObjectNode rows) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM ingestion_batch WHERE hospital_id=? AND period_code=? AND status='rejected' AND created_by=?",
                Integer.class, HOSPITAL, period, system.userId()) > 0) return 0;
        ObjectNode body = json.createObjectNode();
        body.put("sourceCode", SOURCE);
        body.put("period", period);
        body.set("rows", rows.path("rows"));
        Map<String, Object> detail = ingestion.createBatch(system, body, "demo-scenario-rejected-" + period);
        Map<?, ?> batch = (Map<?, ?>) detail.get("batch");
        return "rejected".equals(String.valueOf(batch.get("status"))) ? 1 : 0;
    }

    private ObjectNode batchBody(String versionId, YearMonth period) {
        ObjectNode body = json.createObjectNode();
        body.put("sourceCode", SOURCE);
        body.put("period", period.toString());
        ArrayNode rows = body.putArray("rows");
        List<Map<String, Object>> definitions = jdbc.query(
                "SELECT d.code,d.formula_type,d.unit FROM metric_definition d WHERE d.standard_version_id=? AND d.status='published'"
                        + " AND d.version_no=(SELECT MAX(d2.version_no) FROM metric_definition d2 WHERE d2.standard_version_id=d.standard_version_id AND d2.code=d.code AND d2.status='published') ORDER BY d.code",
                (rs, i) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("code", rs.getString("code"));
                    item.put("formula", rs.getString("formula_type"));
                    item.put("unit", rs.getString("unit"));
                    return item;
                }, versionId);
        List<String> depts = catalog.departmentCodes();
        for (Map<String, Object> definition : definitions) {
            String code = String.valueOf(definition.get("code"));
            if (!catalog.metricCodes().contains(code)) continue;
            // 旧库只补缺失指标，保留既有已应用事实和结果。
            Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM canonical_fact f JOIN ingestion_batch b ON b.id=f.batch_id"
                    + " WHERE b.hospital_id=? AND b.status='applied' AND f.period_code=? AND f.metric_code=?", Integer.class,
                    HOSPITAL, period.toString(), code);
            if (existing != null && existing > 0) continue;
            String formula = String.valueOf(definition.get("formula"));
            String unit = definition.get("unit") == null ? "" : String.valueOf(definition.get("unit"));
            if ("i12".equals(code)) {
                rows.add(row(code, "ALL", null, null, catalog.value(code, "ALL", period), unit));
                continue;
            }
            for (String dept : depts) {
                if ("i10".equals(code) && "NEUR".equals(dept) && period.getMonthValue() == 7) continue;
                double value = catalog.value(code, dept, period);
                rows.add(rowForFormula(code, dept, formula, unit, value, depts.indexOf(dept)));
            }
        }
        return body;
    }

    private ObjectNode rowForFormula(String code, String dept, String formula, String unit,
                                     double value, int deptIndex) {
        if ("year_end_snapshot".equals(formula) || "month_avg".equals(formula)
                || "trend".equals(formula)) {
            return row(code, dept, null, null, value, unit);
        }
        double denominator = "drg_formula".equals(formula) ? 800 + deptIndex * 125
                : "i10".equals(code) ? 12 + deptIndex * 2
                : Set.of("i06", "i07", "i08", "i09").contains(code) ? 500 + deptIndex * 50 : 2000 + deptIndex * 200;
        double numerator = "drg_formula".equals(formula) ? Math.round(value * denominator * 100d) / 100d
                : (double) Math.round(value * denominator / 100d);
        return row(code, dept, numerator, denominator, null, unit);
    }

    private ObjectNode row(String code, String dept, Double numerator, Double denominator,
                           Double value, String unit) {
        ObjectNode row = json.createObjectNode();
        row.put("code", code);
        row.put("dept", dept);
        if (numerator != null) row.put("num", numerator);
        if (denominator != null) row.put("den", denominator);
        if (value != null) row.put("val", value);
        row.put("unit", unit == null ? "" : unit);
        return row;
    }

    private boolean batchExists(String period) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ingestion_batch WHERE hospital_id=? AND period_code=?",
                Integer.class, HOSPITAL, period);
        return count != null && count > 0;
    }

    private String latestVersion() {
        List<String> versions = jdbc.query(
                "SELECT id FROM standard_version WHERE status='published' ORDER BY published_on DESC, version_no DESC",
                (rs, i) -> rs.getString(1));
        return versions.isEmpty() ? null : versions.get(0);
    }

    private boolean marked() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM demo_seed_marker WHERE scenario_code=? AND scenario_version=?",
                Integer.class, catalog.scenarioCode(), catalog.scenarioVersion());
        return count != null && count > 0;
    }

    private int mark(int applied, int rejected) {
        try {
            Map<String,Object> stats = new LinkedHashMap<>();
            stats.put("newAppliedBatches", applied); stats.put("newRejectedBatches", rejected);
            for (String table : List.of("metric_result", "ingestion_batch", "v3_alert")) {
                stats.put(table, jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE hospital_id=?", Integer.class, HOSPITAL));
            }
            stats.put("periods", catalog.periods().size());
            String summary = json.writeValueAsString(stats);
            return jdbc.update("INSERT INTO demo_seed_marker (scenario_code, scenario_version, seed, summary_json, created_at)"
                            + " VALUES (?,?,?,?,?)", catalog.scenarioCode(), catalog.scenarioVersion(), catalog.seed(),
                    summary, java.time.OffsetDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).toString());
        } catch (Exception e) {
            throw new IllegalStateException("无法记录演示场景 marker", e);
        }
    }
}
