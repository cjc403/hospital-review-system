package cn.demo.review.v2.assessment;

import cn.demo.review.v2.V2Exception;
import cn.demo.review.v2.platform.AuditService;
import cn.demo.review.v2.security.AuthContext;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * 前置结论与条款抽查。前置与监测指标分开计算：前置不符合或抽查错误率严格超过 10% 触发延期，
 * 但不改变监测指标本身的符合比例。
 */
@Service
public class AssessmentService {
    private static final Set<String> COMPLIANCE = Set.of("compliant", "non_compliant", "unknown");
    private static final Set<String> WORKFLOW = Set.of("draft", "submitted", "accepted", "rejected");

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public AssessmentService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public String latestPublishedVersion() {
        List<String> ids = jdbc.query(
                "SELECT id FROM standard_version WHERE status='published' ORDER BY created_at DESC",
                (rs, i) -> rs.getString(1));
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** 前置清单：标准定义 + 本院结论（默认 unknown / draft）。 */
    public Map<String, Object> prerequisites(AuthContext ctx, String versionId) {
        String version = versionId == null || versionId.isBlank() ? latestPublishedVersion() : versionId;
        if (version == null) throw V2Exception.conflict("尚无已发布的标准版本");
        List<Map<String, Object>> items = jdbc.query(
                "SELECT d.code, d.section, d.title, d.statement_text, d.system_generated,"
                        + " c.compliance, c.workflow, c.statement, c.updated_by, c.updated_at"
                        + " FROM prereq_definition d LEFT JOIN prereq_conclusion c"
                        + " ON c.version_id=d.version_id AND c.hospital_id=? AND c.code=d.code"
                        + " WHERE d.version_id=? ORDER BY d.code",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("code", rs.getString("code"));
                    m.put("section", rs.getString("section"));
                    m.put("title", rs.getString("title"));
                    m.put("systemGenerated", rs.getInt("system_generated") == 1);
                    m.put("compliance", rs.getString("compliance") == null ? "unknown" : rs.getString("compliance"));
                    m.put("workflow", rs.getString("workflow") == null ? "draft" : rs.getString("workflow"));
                    m.put("statement", rs.getString("statement") == null ? "" : rs.getString("statement"));
                    m.put("updatedBy", rs.getString("updated_by"));
                    m.put("updatedAt", rs.getString("updated_at"));
                    return m;
                }, ctx.hospitalId(), version);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("versionId", version);
        result.put("items", items);
        result.put("nonCompliant", items.stream().filter(m -> "non_compliant".equals(m.get("compliance")))
                .map(m -> m.get("code")).toList());
        return result;
    }

    @Transactional
    public Map<String, Object> setConclusion(AuthContext ctx, String code, JsonNode body, String requestId) {
        ctx.requirePermission("assessment.write");
        String versionId = text(body, "versionId");
        String compliance = choice(body, "compliance", COMPLIANCE);
        String workflow = choice(body, "workflow", WORKFLOW);
        String statement = body.path("statement").asText("");
        List<Map<String, Object>> defs = jdbc.query(
                "SELECT system_generated FROM prereq_definition WHERE version_id=? AND code=?",
                (rs, i) -> Map.<String, Object>of("system", rs.getInt(1)), versionId, code);
        if (defs.isEmpty()) throw V2Exception.notFound("前置条款不存在：" + code);
        if (Integer.valueOf(1).equals(defs.get(0).get("system"))) {
            throw V2Exception.immutable("系统生成的真实性条款不允许人工修改");
        }
        if ("accepted".equals(workflow) && "unknown".equals(compliance)) {
            throw V2Exception.badRequest("结论未知不能被确认接纳");
        }
        int updated = jdbc.update("UPDATE prereq_conclusion SET compliance=?, workflow=?, statement=?, updated_by=?,"
                        + " updated_at=? WHERE hospital_id=? AND version_id=? AND code=?",
                compliance, workflow, statement, ctx.userId(), now(), ctx.hospitalId(), versionId, code);
        if (updated == 0) {
            jdbc.update("INSERT INTO prereq_conclusion (hospital_id, version_id, code, compliance, workflow,"
                            + " statement, updated_by, updated_at) VALUES (?,?,?,?,?,?,?,?)",
                    ctx.hospitalId(), versionId, code, compliance, workflow, statement, ctx.userId(), now());
        }
        audit.record(ctx, requestId, "维护前置结论", "prereq_definition", code,
                "结论=" + compliance + " 流程=" + workflow);
        return prerequisites(ctx, versionId);
    }

    /** 条款抽查：用最新已应用事实对照标准目标线，偏差 ≥10% 记为错误条款。 */
    @Transactional
    public Map<String, Object> createSample(AuthContext ctx, JsonNode body, String requestId) {
        ctx.requirePermission("assessment.write");
        String period = text(body, "period");
        String versionId = latestPublishedVersion();
        if (versionId == null) throw V2Exception.conflict("尚无已发布的标准版本");

        Map<String, Double> targets = new HashMap<>();
        List<Map<String, Object>> definitions = jdbc.query(
                "SELECT code, target_value FROM indicator_definition WHERE version_id=?",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("code", rs.getString("code"));
                    m.put("target", rs.getObject("target_value") == null ? null : rs.getDouble("target_value"));
                    return m;
                }, versionId);
        for (Map<String, Object> def : definitions) {
            targets.put((String) def.get("code"), (Double) def.get("target"));
        }
        List<Map<String, Object>> facts = jdbc.query(
                "SELECT f.indicator_code, f.metric_value FROM metric_fact f"
                        + " JOIN collection_batch b ON b.id=f.batch_id"
                        + " WHERE b.hospital_id=? AND b.status='applied' ORDER BY f.period_code DESC",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("code", rs.getString("indicator_code"));
                    m.put("value", rs.getObject("metric_value"));
                    return m;
                }, ctx.hospitalId());
        Map<String, Double> latest = new LinkedHashMap<>();
        for (Map<String, Object> fact : facts) {
            String code = (String) fact.get("code");
            if (!latest.containsKey(code) && fact.get("value") != null) {
                latest.put(code, (Double) fact.get("value"));
            }
        }
        if (latest.isEmpty()) throw V2Exception.conflict("尚无已应用数据，不能发起条款抽查");

        List<Map<String, Object>> items = new ArrayList<>();
        int errors = 0;
        for (Map.Entry<String, Double> entry : latest.entrySet()) {
            Double target = targets.get(entry.getKey());
            double value = entry.getValue();
            Double deviation = null;
            boolean wrong = false;
            if (target != null && target != 0) {
                deviation = Math.abs(value - target) / Math.abs(target);
                wrong = deviation >= 0.1;
            }
            if (wrong) errors++;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("indicatorCode", entry.getKey());
            item.put("homeValue", target);
            item.put("factValue", value);
            item.put("deviation", deviation);
            item.put("result", wrong ? "error" : "matched");
            items.add(item);
        }
        int sample = items.size();
        double rate = sample == 0 ? 0 : (double) errors / sample;
        String id = "sample-" + UUID.randomUUID();
        jdbc.update("INSERT INTO audit_sample (id, hospital_id, version_id, period_code, sample_count, error_count,"
                        + " error_rate, created_by, created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                id, ctx.hospitalId(), versionId, period, sample, errors, rate, ctx.userId(), now());
        audit.record(ctx, requestId, "发起条款抽查", "audit_sample", id,
                "周期=" + period + " 错误=" + errors + "/" + sample);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("period", period);
        result.put("sampleCount", sample);
        result.put("errorCount", errors);
        result.put("errorRate", rate);
        result.put("items", items);
        return result;
    }

    public Map<String, Object> samples(AuthContext ctx, int limit) {
        List<Map<String, Object>> items = jdbc.query(
                "SELECT id, period_code, sample_count, error_count, error_rate, created_at"
                        + " FROM audit_sample WHERE hospital_id=? ORDER BY created_at DESC LIMIT ?",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("period", rs.getString("period_code"));
                    m.put("sampleCount", rs.getInt("sample_count"));
                    m.put("errorCount", rs.getInt("error_count"));
                    m.put("errorRate", rs.getObject("error_rate"));
                    m.put("createdAt", rs.getString("created_at"));
                    return m;
                }, ctx.hospitalId(), Math.min(Math.max(limit, 1), 100));
        return Map.of("items", items);
    }

    /** 供试算使用：最新一次抽查的错误率与不符合前置编码。 */
    public Map<String, Object> deferralInputs(String hospitalId, String versionId) {
        List<String> nonCompliant = jdbc.query(
                "SELECT code FROM prereq_conclusion WHERE hospital_id=? AND version_id=? AND compliance='non_compliant'",
                (rs, i) -> rs.getString(1), hospitalId, versionId);
        List<Map<String, Object>> samples = jdbc.query(
                "SELECT id, error_rate FROM audit_sample WHERE hospital_id=? ORDER BY created_at DESC",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("errorRate", rs.getObject("error_rate"));
                    return m;
                }, hospitalId);
        Double rate = samples.isEmpty() || samples.get(0).get("errorRate") == null
                ? null : ((Number) samples.get(0).get("errorRate")).doubleValue();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nonCompliantCodes", nonCompliant);
        result.put("latestSampleId", samples.isEmpty() ? null : samples.get(0).get("id"));
        result.put("errorRate", rate);
        return result;
    }

    private static String text(JsonNode body, String name) {
        JsonNode node = body.get(name);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw V2Exception.badRequest(name + " 不能为空");
        }
        return node.asText().trim();
    }

    private static String choice(JsonNode body, String name, Set<String> allowed) {
        String value = text(body, name);
        if (!allowed.contains(value)) throw V2Exception.badRequest(name + " 取值不合法");
        return value;
    }

    private static String now() {
        return OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).toString();
    }
}
