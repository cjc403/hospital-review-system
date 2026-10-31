package cn.demo.review.v2.score;

import cn.demo.review.domain.Scoring;
import cn.demo.review.v2.V2Exception;
import cn.demo.review.v2.assessment.AssessmentService;
import cn.demo.review.v2.platform.AuditService;
import cn.demo.review.v2.quality.QualityService;
import cn.demo.review.v2.security.AuthContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * 周期试算：只消费已应用批次的事实，产出不可变快照。
 * 快照记录口径版本、批次、材料版本，报告只读快照，后续业务变化不改写历史报告。
 */
@Service
public class ScoreService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuditService audit;
    private final QualityService quality;
    private final AssessmentService assessment;

    public ScoreService(JdbcTemplate jdbc, ObjectMapper json, AuditService audit, QualityService quality,
                        AssessmentService assessment) {
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
        this.quality = quality;
        this.assessment = assessment;
    }

    @Transactional
    public Map<String, Object> create(AuthContext ctx, JsonNode body, String requestId) {
        ctx.require("review_office");
        String versionId = body.path("versionId").asText("");
        if (versionId.isBlank()) throw V2Exception.badRequest("versionId 不能为空");
        int start = body.path("cycleStart").asInt(0);
        int end = body.path("cycleEnd").asInt(0);
        if (start < 2023 || end > 2026 || start > end || end - start > 3) {
            throw V2Exception.badRequest("评审周期须在 2023 至 2026 年之间且包含 1 至 4 年");
        }
        List<Map<String, Object>> versions = jdbc.query(
                "SELECT status FROM standard_version WHERE id=?", (rs, i) -> Map.<String, Object>of("status", rs.getString(1)),
                versionId);
        if (versions.isEmpty()) throw V2Exception.notFound("标准版本不存在");
        if (!"published".equals(versions.get(0).get("status"))) throw V2Exception.conflict("只能用已发布的标准版本试算");

        List<Map<String, Object>> definitions = jdbc.query(
                "SELECT code, name, unit, direction, score_type, annual_agg, target_value FROM indicator_definition"
                        + " WHERE version_id=? ORDER BY code",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("code", rs.getString("code"));
                    m.put("name", rs.getString("name"));
                    m.put("unit", rs.getString("unit"));
                    m.put("direction", rs.getString("direction"));
                    m.put("scoreType", rs.getString("score_type"));
                    m.put("annualAgg", rs.getString("annual_agg"));
                    m.put("target", rs.getObject("target_value"));
                    return m;
                }, versionId);
        if (definitions.isEmpty()) throw V2Exception.conflict("该标准版本没有指标定义");

        List<String> batchIds = quality.appliedBatchIds(ctx.hospitalId(), start, end);
        if (batchIds.isEmpty()) throw V2Exception.conflict("尚无已应用的数据批次，不能试算");

        List<Map<String, Object>> facts = jdbc.query(
                "SELECT f.indicator_code, f.period_code, f.numerator, f.denominator, f.metric_value FROM metric_fact f"
                        + " JOIN collection_batch b ON b.id=f.batch_id"
                        + " WHERE b.hospital_id=? AND b.status='applied' AND b.period_code>=? AND b.period_code<=?"
                        + " ORDER BY f.period_code",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("code", rs.getString("indicator_code"));
                    m.put("period", rs.getString("period_code"));
                    m.put("numerator", rs.getObject("numerator"));
                    m.put("denominator", rs.getObject("denominator"));
                    m.put("value", rs.getObject("metric_value"));
                    return m;
                }, ctx.hospitalId(), start + "-01", end + "-12");

        Map<String, Map<Integer, List<Double>>> values = new HashMap<>();
        Map<String, Map<Integer, double[]>> formulaTotals = new HashMap<>();
        for (Map<String, Object> fact : facts) {
            String code = (String) fact.get("code");
            String period = (String) fact.get("period");
            int year = Integer.parseInt(period.substring(0, 4));
            if (year < start || year > end) continue;
            Number numerator = (Number) fact.get("numerator");
            Number denominator = (Number) fact.get("denominator");
            if (numerator != null && denominator != null && Double.isFinite(numerator.doubleValue())
                    && Double.isFinite(denominator.doubleValue()) && denominator.doubleValue() > 0) {
                double[] totals = formulaTotals.computeIfAbsent(code, k -> new HashMap<>())
                        .computeIfAbsent(year, k -> new double[2]);
                totals[0] += numerator.doubleValue();
                totals[1] += denominator.doubleValue();
            }
            Double value = (Double) fact.get("value");
            if (value == null) continue;
            values.computeIfAbsent(code, k -> new HashMap<>()).computeIfAbsent(year, k -> new ArrayList<>()).add(value);
        }
        if (values.isEmpty() && formulaTotals.isEmpty()) {
            throw V2Exception.conflict("所选周期没有可用的已应用事实");
        }

        String runId = "run-" + UUID.randomUUID();
        String now = now();
        int included = 0, conformCount = 0;
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> def : definitions) {
            String code = (String) def.get("code");
            String annualAgg = String.valueOf(def.get("annualAgg"));
            Map<Integer, List<Double>> byYear = values.getOrDefault(code, Map.of());
            List<Double> series = new ArrayList<>();
            List<Map<String, Object>> yearValues = new ArrayList<>();
            for (int year = start; year <= end; year++) {
                List<Double> points = byYear.get(year);
                Double annual = null;
                if ("drg_formula".equals(annualAgg)) {
                    double[] totals = formulaTotals.getOrDefault(code, Map.of()).get(year);
                    if (totals != null && totals[1] > 0) {
                        annual = totals[0] / totals[1];
                    }
                } else if (points != null && !points.isEmpty()) {
                    if ("year_end_snapshot".equals(annualAgg)) {
                        // 年末快照指标取该年度最新批次值。
                        annual = points.get(points.size() - 1);
                    } else {
                        annual = points.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                    }
                    annual = Math.round(annual * 10000d) / 10000d;
                }
                series.add(annual);
                Map<String, Object> yearValue = new LinkedHashMap<>();
                yearValue.put("year", year);
                yearValue.put("value", annual);
                yearValues.add(yearValue);
            }
            String direction = String.valueOf(def.get("direction"));
            String scoreType = String.valueOf(def.get("scoreType"));
            double target = ((Number) def.get("target")).doubleValue();
            Scoring.Result result = Scoring.evaluate(series, direction, scoreType, target);
            if (result.conform() != null) {
                included++;
                if (result.conform()) conformCount++;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("indicatorCode", code);
            item.put("name", def.get("name"));
            item.put("unit", def.get("unit"));
            item.put("yearValues", yearValues);
            item.put("mergedValue", result.mergedValue());
            item.put("conform", result.conform());
            item.put("reasonCode", result.reasonCode());
            items.add(item);
            jdbc.update("INSERT INTO score_item (id, run_id, indicator_code, name, unit, year_values, merged_value,"
                            + " conform, reason_code) VALUES (?,?,?,?,?,?,?,?,?)",
                    "si-" + UUID.randomUUID(), runId, code, def.get("name"), def.get("unit"),
                    serializeYearValues(yearValues), result.mergedValue(),
                    result.conform() == null ? null : (result.conform() ? 1 : 0), result.reasonCode());
        }
        Map<String, Object> deferral = assessment.deferralInputs(ctx.hospitalId(), versionId);
        @SuppressWarnings("unchecked")
        List<String> nonCompliantCodes = (List<String>) deferral.getOrDefault("nonCompliantCodes", List.of());
        boolean nonCompliant = !nonCompliantCodes.isEmpty();
        Object rawRate = deferral.get("errorRate");
        Double errorRate = rawRate == null ? null : ((Number) rawRate).doubleValue();
        String grade = Scoring.grade(conformCount, included, nonCompliant, errorRate == null ? 0d : errorRate);
        String deferredReason = buildDeferralReason(included, nonCompliantCodes, errorRate);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("runId", runId);
        snapshot.put("versionId", versionId);
        snapshot.put("cycleStart", start);
        snapshot.put("cycleEnd", end);
        snapshot.put("batchIds", batchIds);
        snapshot.put("factCount", facts.size());
        snapshot.put("evidence", approvedEvidence(ctx.hospitalId()));
        snapshot.put("prereqNonCompliantCodes", nonCompliantCodes);
        snapshot.put("auditSampleId", deferral.get("latestSampleId"));
        snapshot.put("auditErrorRate", errorRate);
        snapshot.put("createdAt", now);
        String snapshotJson;
        try {
            snapshotJson = json.writeValueAsString(snapshot);
        } catch (Exception e) {
            throw new IllegalStateException("快照序列化失败", e);
        }
        double ratio = included == 0 ? 0 : (double) conformCount / included;
        jdbc.update("INSERT INTO score_run (id, hospital_id, version_id, cycle_start, cycle_end, status, grade,"
                        + " conform_count, included_count, ratio, deferred_reason, snapshot_json, created_by, created_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                runId, ctx.hospitalId(), versionId, start, end, "snapshot", grade, conformCount, included,
                ratio, deferredReason, snapshotJson, ctx.userId(), now);
        audit.record(ctx, requestId, "创建周期试算", "score_run", runId,
                "周期=" + start + "-" + end + " 符合=" + conformCount + "/" + included + " 批次=" + batchIds.size());
        return get(ctx, runId);
    }

    /** 试算列表：只返回本院快照摘要，按创建时间倒序。 */
    public Map<String, Object> list(AuthContext ctx, int limit) {
        List<Map<String, Object>> items = jdbc.query(
                "SELECT r.id, r.version_id, v.version_no, r.cycle_start, r.cycle_end, r.grade, r.conform_count,"
                        + " r.included_count, r.ratio, r.deferred_reason, r.created_at FROM score_run r"
                        + " JOIN standard_version v ON v.id=r.version_id WHERE r.hospital_id=?"
                        + " ORDER BY r.created_at DESC LIMIT ?",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("versionId", rs.getString("version_id"));
                    m.put("versionNo", rs.getInt("version_no"));
                    m.put("cycleStart", rs.getInt("cycle_start"));
                    m.put("cycleEnd", rs.getInt("cycle_end"));
                    m.put("grade", rs.getString("grade"));
                    m.put("conformCount", rs.getInt("conform_count"));
                    m.put("includedCount", rs.getInt("included_count"));
                    m.put("ratio", rs.getObject("ratio"));
                    m.put("deferredReason", rs.getString("deferred_reason"));
                    m.put("createdAt", rs.getString("created_at"));
                    return m;
                }, ctx.hospitalId(), Math.min(Math.max(limit, 1), 100));
        return Map.of("items", items);
    }

    public Map<String, Object> get(AuthContext ctx, String runId) {        Map<String, Object> run = runRow(ctx, runId);
        List<Map<String, Object>> items = jdbc.query(
                "SELECT indicator_code, name, unit, year_values, merged_value, conform, reason_code"
                        + " FROM score_item WHERE run_id=? ORDER BY indicator_code",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("indicatorCode", rs.getString("indicator_code"));
                    m.put("name", rs.getString("name"));
                    m.put("unit", rs.getString("unit"));
                    m.put("yearValues", parseYearValues(rs.getString("year_values")));
                    Object merged = rs.getObject("merged_value");
                    m.put("mergedValue", merged);
                    Object conform = rs.getObject("conform");
                    m.put("conform", conform == null ? null : ((Number) conform).intValue() == 1);
                    m.put("reasonCode", rs.getString("reason_code"));
                    return m;
                }, runId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("run", run);
        result.put("items", items);
        return result;
    }

    public Map<String, Object> compare(AuthContext ctx, String leftId, String rightId) {
        Map<String, Object> left = get(ctx, leftId);
        Map<String, Object> right = get(ctx, rightId);
        Map<String, Map<String, Object>> leftItems = byCode(left);
        Map<String, Map<String, Object>> rightItems = byCode(right);
        List<String> added = new ArrayList<>(), recovered = new ArrayList<>(), worsened = new ArrayList<>(), changedRule = new ArrayList<>();
        for (String code : rightItems.keySet()) {
            Map<String, Object> b = rightItems.get(code);
            Map<String, Object> a = leftItems.get(code);
            if (a == null) {
                added.add(code);
                continue;
            }
            Boolean aConform = (Boolean) a.get("conform");
            Boolean bConform = (Boolean) b.get("conform");
            if (Boolean.FALSE.equals(aConform) && Boolean.TRUE.equals(bConform)) recovered.add(code);
            if (Boolean.TRUE.equals(aConform) && Boolean.FALSE.equals(bConform)) worsened.add(code);
            if (!Objects.equals(a.get("reasonCode"), b.get("reasonCode"))) changedRule.add(code);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("left", left.get("run"));
        result.put("right", right.get("run"));
        result.put("added", added);
        result.put("recovered", recovered);
        result.put("worsened", worsened);
        result.put("changedRule", changedRule);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> byCode(Map<String, Object> run) {
        Map<String, Map<String, Object>> map = new LinkedHashMap<>();
        for (Map<String, Object> item : (List<Map<String, Object>>) run.get("items")) {
            map.put((String) item.get("indicatorCode"), item);
        }
        return map;
    }

    public byte[] report(AuthContext ctx, String runId, String requestId) throws Exception {
        ctx.require("review_office", "leader", "observer", "indicator_admin", "evidence_admin");
        Map<String, Object> run = get(ctx, runId);
        Map<String, Object> snapshot = parseSnapshot((String) runRow(ctx, runId).get("snapshot"));
        byte[] bytes = V2DocxReport.render(run, snapshot);
        audit.record(ctx, requestId, "导出试算报告", "score_run", runId, "快照报告，含批次与材料版本");
        return bytes;
    }

    private Map<String, Object> runRow(AuthContext ctx, String runId) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT r.id, r.version_id, r.cycle_start, r.cycle_end, r.status, r.grade, r.conform_count,"
                        + " r.included_count, r.ratio, r.deferred_reason, r.snapshot_json, r.created_by, r.created_at,"
                        + " v.version_no, p.code AS package_code, p.title AS package_title"
                        + " FROM score_run r JOIN standard_version v ON v.id=r.version_id"
                        + " JOIN standard_package p ON p.id=v.package_id WHERE r.id=? AND r.hospital_id=?",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("versionId", rs.getString("version_id"));
                    m.put("versionNo", rs.getInt("version_no"));
                    m.put("packageCode", rs.getString("package_code"));
                    m.put("packageTitle", rs.getString("package_title"));
                    m.put("cycleStart", rs.getInt("cycle_start"));
                    m.put("cycleEnd", rs.getInt("cycle_end"));
                    m.put("status", rs.getString("status"));
                    m.put("grade", rs.getString("grade"));
                    m.put("conformCount", rs.getInt("conform_count"));
                    m.put("includedCount", rs.getInt("included_count"));
                    m.put("ratio", rs.getObject("ratio"));
                    m.put("deferredReason", rs.getString("deferred_reason"));
                    m.put("createdBy", rs.getString("created_by"));
                    m.put("createdAt", rs.getString("created_at"));
                    m.put("snapshot", rs.getString("snapshot_json"));
                    return m;
                }, runId, ctx.hospitalId());
        if (rows.isEmpty()) throw V2Exception.notFound("试算不存在或不在可见范围");
        return rows.get(0);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseSnapshot(String value) {
        try {
            return json.readValue(value, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private List<Map<String, Object>> approvedEvidence(String hospitalId) {
        return jdbc.query(
                "SELECT i.id AS item_id, i.title, v.id AS version_id, v.version_no, v.sha256, v.file_name"
                        + " FROM evidence_item i JOIN evidence_version v ON v.item_id=i.id"
                        + " WHERE i.hospital_id=? AND i.status='approved'"
                        + " AND v.version_no=(SELECT MAX(v2.version_no) FROM evidence_version v2 WHERE v2.item_id=i.id)",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("itemId", rs.getString("item_id"));
                    m.put("title", rs.getString("title"));
                    m.put("versionId", rs.getString("version_id"));
                    m.put("versionNo", rs.getInt("version_no"));
                    m.put("sha256", rs.getString("sha256"));
                    m.put("fileName", rs.getString("file_name"));
                    return m;
                }, hospitalId);
    }

    /** 延期原因：前置不符合与抽查超阈值分开表述，便于报告直接引用。 */
    private static String buildDeferralReason(int included, List<String> nonCompliantCodes, Double errorRate) {
        List<String> reasons = new ArrayList<>();
        if (nonCompliantCodes != null && !nonCompliantCodes.isEmpty()) {
            reasons.add("前置不符合：" + String.join("、", nonCompliantCodes));
        }
        if (errorRate != null && errorRate > 0.1) {
            reasons.add("最新条款抽查错误率 " + Math.round(errorRate * 10000) / 100d + "% 严格超过 10%");
        }
        if (included == 0) reasons.add("无有效年度事实，不给出等次");
        return String.join("；", reasons);
    }

    private String serializeYearValues(List<Map<String, Object>> yearValues) {
        try {
            return json.writeValueAsString(yearValues);
        } catch (Exception e) {
            throw new IllegalStateException("年度值序列化失败", e);
        }
    }

    private List<Map<String, Object>> parseYearValues(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            List<Map<String, Object>> parsed = json.readValue(raw,
                    new TypeReference<List<Map<String, Object>>>() {});
            List<Map<String, Object>> normalized = new ArrayList<>();
            for (Map<String, Object> item : parsed) {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("year", ((Number) item.get("year")).intValue());
                Object rawValue = item.get("value");
                value.put("value", rawValue == null ? null : ((Number) rawValue).doubleValue());
                normalized.add(value);
            }
            return normalized;
        } catch (Exception ignored) {
            // 兼容修复前已落库的 "2023:1.2; 2024:" 文本格式。
            List<Map<String, Object>> legacy = new ArrayList<>();
            for (String token : raw.split(";")) {
                String[] pair = token.trim().split(":", 2);
                if (pair.length != 2) continue;
                try {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("year", Integer.parseInt(pair[0].trim()));
                    String number = pair[1].trim();
                    value.put("value", number.isEmpty() ? null : Double.parseDouble(number));
                    legacy.add(value);
                } catch (NumberFormatException ignoredLegacy) {
                    return List.of();
                }
            }
            return legacy;
        }
    }

    private static String now() {
        return OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).toString();
    }
}
