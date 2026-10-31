package cn.demo.review.v3.ingestion;

import cn.demo.review.v2.V2Exception;
import cn.demo.review.v2.platform.AuditService;
import cn.demo.review.v2.platform.Hashes;
import cn.demo.review.v2.security.AuthContext;
import cn.demo.review.v3.metric.FormulaEngine;
import cn.demo.review.v3.metric.MetricDefinitionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.YearMonth;
import java.util.*;

/**
 * 数据接入：映射 → 编码清洗 → 质量校验 → 标准事实 → 结果与预警。
 * 错误批次只落库、不产出结果；更正只能重放生成新批次。
 */
@Service
public class IngestionService {
    private static final String RULE_VERSION = "v3-rule-1";
    private static final Set<String> RATIO_TYPES = Set.of("ratio", "weighted_ratio", "drg_formula");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuditService audit;
    private final MetricDefinitionService definitions;

    public IngestionService(JdbcTemplate jdbc, ObjectMapper json, AuditService audit,
                            MetricDefinitionService definitions) {
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
        this.definitions = definitions;
    }

    // ---------- 映射 ----------

    public Map<String, Object> mappings(AuthContext ctx, String sourceCode) {
        List<Map<String, Object>> items = jdbc.query(
                "SELECT source_code, mapping_json, version_no, updated_at FROM source_mapping"
                        + " WHERE hospital_id=? AND (? IS NULL OR source_code=?) ORDER BY source_code",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("sourceCode", rs.getString("source_code"));
                    m.put("fields", readTree(rs.getString("mapping_json")).path("fields"));
                    m.put("codeMap", readTree(rs.getString("mapping_json")).path("codeMap"));
                    m.put("version", rs.getInt("version_no"));
                    m.put("updatedAt", rs.getString("updated_at"));
                    return m;
                }, ctx.hospitalId(), blank(sourceCode) ? null : sourceCode, sourceCode);
        return Map.of("items", items);
    }

    @Transactional
    public Map<String, Object> saveMapping(AuthContext ctx, JsonNode body, String requestId) {
        ctx.require("review_office");
        String sourceCode = text(body, "sourceCode");
        JsonNode fields = body.path("fields");
        if (!fields.isObject() || fields.path("metricCode").asText("").isBlank()) {
            throw new V2Exception(400, "mapping_incomplete", "映射必须至少定义 fields.metricCode");
        }
        String mapping = body.toString();
        Integer next = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_no),0)+1 FROM source_mapping WHERE hospital_id=? AND source_code=?",
                Integer.class, ctx.hospitalId(), sourceCode);
        int updated = jdbc.update("UPDATE source_mapping SET mapping_json=?, version_no=?, updated_by=?, updated_at=?"
                        + " WHERE hospital_id=? AND source_code=?",
                mapping, next, ctx.userId(), now(), ctx.hospitalId(), sourceCode);
        if (updated == 0) {
            jdbc.update("INSERT INTO source_mapping (id, hospital_id, source_code, mapping_json, version_no,"
                            + " updated_by, updated_at) VALUES (?,?,?,?,?,?,?)",
                    "map-" + UUID.randomUUID(), ctx.hospitalId(), sourceCode, mapping, next, ctx.userId(), now());
        }
        audit.record(ctx, requestId, "更新来源映射", "source_mapping", sourceCode, "映射版本=" + next);
        return mappings(ctx, sourceCode).get("items") instanceof List<?> items && !items.isEmpty()
                ? (Map<String, Object>) items.get(0) : Map.of();
    }

    // ---------- 批次 ----------

    @Transactional
    public Map<String, Object> createBatch(AuthContext ctx, JsonNode body, String requestId) {
        ctx.require("review_office", "indicator_admin");
        String sourceCode = text(body, "sourceCode");
        String period = text(body, "period");
        validatePeriod(period);
        JsonNode rows = body.path("rows");
        if (!rows.isArray() || rows.isEmpty()) throw V2Exception.badRequest("rows 必须是非空数组");
        Map<String, Object> source = source(ctx, sourceCode);
        Map<String, Object> mapping = currentMapping(ctx, sourceCode);
        if (mapping == null) throw new V2Exception(400, "mapping_incomplete", "该数据源尚未配置字段映射");
        String versionId = definitions.latestVersion();
        if (versionId == null) throw V2Exception.conflict("尚无已发布的标准版本");

        return persistBatch(ctx, sourceCode, (String) source.get("id"), versionId, period, rows,
                mapping, null, requestId);
    }

    @Transactional
    public Map<String, Object> replay(AuthContext ctx, String batchId, String requestId) {
        ctx.require("review_office", "indicator_admin");
        Map<String, Object> batch = batchRow(ctx, batchId);
        List<Map<String, Object>> sources = jdbc.query("SELECT code FROM data_source WHERE id=?",
                (rs, i) -> Map.of("code", rs.getString(1)), batch.get("sourceId"));
        if (sources.isEmpty()) throw V2Exception.conflict("原批次数据源已不存在");
        String raw = String.valueOf(batch.get("rawJson"));
        JsonNode rows;
        try {
            rows = json.readTree(raw);
        } catch (Exception e) {
            throw V2Exception.conflict("原批次没有可重放的原始数据");
        }
        Map<String, Object> mapping = currentMapping(ctx, (String) sources.get(0).get("code"));
        if (mapping == null) throw new V2Exception(400, "mapping_incomplete", "该数据源尚未配置字段映射");
        return persistBatch(ctx, (String) sources.get(0).get("code"), (String) batch.get("sourceId"),
                (String) batch.get("versionId"), String.valueOf(batch.get("period")), rows, mapping,
                batchId, requestId);
    }

    private Map<String, Object> persistBatch(AuthContext ctx, String sourceCode, String sourceId, String versionId,
                                             String period, JsonNode rows, Map<String, Object> mapping,
                                             String replayOf, String requestId) {
        JsonNode fields = (JsonNode) mapping.get("fields");
        JsonNode codeMap = (JsonNode) mapping.get("codeMap");
        List<Map<String, Object>> issues = new ArrayList<>();
        List<Canonical> canonical = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (int index = 0; index < rows.size(); index++) {
            JsonNode row = rows.get(index);
            int rowNo = index + 1;
            String rawMetric = value(row, fields.path("metricCode").asText(""));
            String metricCode = mapCode(codeMap, "metric", rawMetric);
            if (blank(metricCode)) {
                issues.add(issue(rowNo, "missing_required", "metricCode", "缺少指标编码", "error"));
                continue;
            }
            Map<String, Object> definition = definitions.current(ctx.hospitalId(), versionId, metricCode);
            if (definition == null) {
                issues.add(issue(rowNo, "unknown_metric", "metricCode", "指标未定义：" + metricCode, "error"));
                continue;
            }
            String rawDept = value(row, fields.path("deptCode").asText(""));
            String deptCode = blank(rawDept) ? "ALL" : mapCode(codeMap, "dept", rawDept);
            if (deptCode == null || deptCode.isBlank()) {
                issues.add(issue(rowNo, "unmapped_dept", "deptCode", "科室无法映射：" + rawDept, "error"));
                continue;
            }
            if (!"ALL".equals(deptCode) && !departmentExists(ctx.hospitalId(), deptCode)) {
                issues.add(issue(rowNo, "unmapped_dept", "deptCode", "科室不存在于本院：" + deptCode, "error"));
                continue;
            }
            Double numerator = number(row, fields.path("numerator").asText(""));
            Double denominator = number(row, fields.path("denominator").asText(""));
            Double value = number(row, fields.path("value").asText(""));
            String unit = value(row, fields.path("unit").asText(""));
            String formulaType = String.valueOf(definition.get("formulaType"));
            String expectedUnit = (String) definition.get("unit");

            if (!blank(unit) && !blank(expectedUnit) && !unit.equals(expectedUnit)) {
                issues.add(issue(rowNo, "unit_mismatch", "unit",
                        "单位 " + unit + " 与定义 " + expectedUnit + " 不一致", "error"));
                continue;
            }
            if (RATIO_TYPES.contains(formulaType)) {
                if (numerator == null || denominator == null) {
                    issues.add(issue(rowNo, "missing_required", "numerator/denominator",
                            "该公式类型需要分子与分母", "error"));
                    continue;
                }
                if (denominator <= 0) {
                    issues.add(issue(rowNo, "denominator_not_positive", "denominator", "分母必须大于 0", "error"));
                    continue;
                }
                if (numerator > denominator && !"drg_formula".equals(formulaType)) {
                    // drg_formula 的分子是加权和（权重×病例数），本来就大于病例数，不能套用比例规则
                    issues.add(issue(rowNo, "numerator_over_denominator", "numerator", "分子不能大于分母", "error"));
                    continue;
                }
            } else if (value == null) {
                issues.add(issue(rowNo, "missing_required", "value", "该公式类型需要数值", "error"));
                continue;
            }
            if (!seen.add(metricCode + "|" + deptCode)) {
                issues.add(issue(rowNo, "duplicate_fact", "metricCode", "同批重复事实：" + metricCode + "/" + deptCode, "error"));
                continue;
            }
            if (periodOverlaps(ctx.hospitalId(), metricCode, deptCode, period)) {
                issues.add(issue(rowNo, "period_overlap", "period",
                        "该周期已有已应用事实：" + metricCode + "/" + deptCode, "error"));
                continue;
            }
            Double previous = previousAppliedValue(ctx.hospitalId(), metricCode, deptCode, period);
            Double currentValue = value != null ? value
                    : (numerator != null && denominator != null ? scale(expectedUnit, numerator, denominator) : null);
            if (previous != null && previous != 0 && currentValue != null) {
                double change = Math.abs(currentValue - previous) / Math.abs(previous);
                if (change > 0.5) {
                    issues.add(issue(rowNo, "abnormal_jump", "value",
                            "较上一已应用期 %.4f 变化 %.0f%%".formatted(previous, change * 100), "warning"));
                }
            }
            canonical.add(new Canonical(metricCode, deptCode, numerator, denominator, currentValue,
                    blank(unit) ? expectedUnit : unit, 0));
        }

        boolean rejected = issues.stream().anyMatch(i -> "error".equals(i.get("severity")));
        String batchId = "ing-" + UUID.randomUUID();
        String checksum = Hashes.sha256Hex(rows.toString() + "|" + period);
        String status = rejected ? "rejected" : "validated";
        jdbc.update("INSERT INTO ingestion_batch (id, hospital_id, source_id, standard_version_id, period_code,"
                        + " status, record_count, checksum, mapping_version, reject_reason, replay_of, raw_json,"
                        + " created_by, created_at, applied_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                batchId, ctx.hospitalId(), sourceId, versionId, period, status, rows.size(), checksum,
                mapping.get("version"), rejected ? "存在 " + countErrors(issues) + " 条质量错误，批次已阻断" : "",
                replayOf, rows.toString(), ctx.userId(), now(), null);
        for (Canonical fact : canonical) {
            jdbc.update("INSERT INTO canonical_fact (id, batch_id, metric_code, dept_code, period_code, numerator,"
                            + " denominator, metric_value, unit, excluded_count) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    "fact-" + UUID.randomUUID(), batchId, fact.metricCode(), fact.deptCode(), period,
                    fact.numerator(), fact.denominator(), fact.value(), fact.unit(), fact.excluded());
        }
        for (Map<String, Object> issue : issues) {
            jdbc.update("INSERT INTO v3_quality_issue (id, batch_id, row_no, rule, field, message, severity)"
                            + " VALUES (?,?,?,?,?,?,?)",
                    "qi-" + UUID.randomUUID(), batchId, issue.get("rowNo"), issue.get("rule"),
                    issue.get("field"), issue.get("message"), issue.get("severity"));
        }
        jdbc.update("INSERT INTO v3_trace (id, batch_id, metric_code, formula_type, rule_version, executed_at,"
                        + " duration_ms, detail) VALUES (?,?,?,?,?,?,?,?)",
                "trace-" + UUID.randomUUID(), batchId, null, null, RULE_VERSION, now(), 0L,
                "映射版本=" + mapping.get("version") + "；事实=" + canonical.size() + "；错误=" + countErrors(issues));
        audit.record(ctx, requestId, "导入 v3 数据批次", "ingestion_batch", batchId,
                "来源=" + sourceCode + " 周期=" + period + " 状态=" + status);
        return detail(ctx, batchId);
    }

    /** 应用批次：计算全院与各科室结果，生成预警和血缘。 */
    @Transactional
    public Map<String, Object> apply(AuthContext ctx, String batchId, String requestId) {
        ctx.require("review_office", "indicator_admin");
        Map<String, Object> batch = batchRow(ctx, batchId);
        String status = String.valueOf(batch.get("status"));
        if ("rejected".equals(status)) throw V2Exception.qualityBlocked("质量不合格批次不能应用");
        if ("applied".equals(status)) throw V2Exception.immutable("该批次已应用，不可重复应用");
        String versionId = String.valueOf(batch.get("versionId"));
        String period = String.valueOf(batch.get("period"));

        List<Map<String, Object>> facts = jdbc.query(
                "SELECT metric_code, dept_code, numerator, denominator, metric_value, unit FROM canonical_fact"
                        + " WHERE batch_id=? ORDER BY metric_code, dept_code",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("metricCode", rs.getString("metric_code"));
                    m.put("deptCode", rs.getString("dept_code"));
                    m.put("numerator", rs.getObject("numerator"));
                    m.put("denominator", rs.getObject("denominator"));
                    m.put("value", rs.getObject("metric_value"));
                    m.put("unit", rs.getString("unit"));
                    return m;
                }, batchId);

        Set<String> depts = new LinkedHashSet<>();
        depts.add("ALL");
        for (Map<String, Object> fact : facts) depts.add(String.valueOf(fact.get("deptCode")));

        Set<String> metrics = new LinkedHashSet<>();
        for (Map<String, Object> fact : facts) metrics.add(String.valueOf(fact.get("metricCode")));

        int created = 0;
        for (String metricCode : metrics) {
            Map<String, Object> definition = definitions.current(ctx.hospitalId(), versionId, metricCode);
            if (definition == null) continue;
            FormulaEngine.Spec spec = definitions.spec(definition);
            for (String dept : depts) {
                List<FormulaEngine.Fact> scoped = new ArrayList<>();
                for (Map<String, Object> fact : facts) {
                    if (!metricCode.equals(fact.get("metricCode"))) continue;
                    scoped.add(new FormulaEngine.Fact(period, String.valueOf(fact.get("deptCode")),
                            toDouble(fact.get("numerator")), toDouble(fact.get("denominator")),
                            toDouble(fact.get("value")), (String) fact.get("unit")));
                }
                FormulaEngine.Result result = FormulaEngine.compute(spec, scoped, period, dept);
                String resultId = insertResult(ctx, definition, spec, result, period, dept, batchId, versionId);
                created++;
                recordLineage(ctx.hospitalId(), "batch", batchId, "result", resultId, "computed");
                raiseAlerts(ctx, result, spec, dept, period, metricCode, resultId, (String) definition.get("name"),
                        previousResultValue(ctx.hospitalId(), metricCode, dept, period));
            }
            jdbc.update("INSERT INTO v3_trace (id, batch_id, metric_code, formula_type, rule_version, executed_at,"
                            + " duration_ms, detail) VALUES (?,?,?,?,?,?,?,?)",
                    "trace-" + UUID.randomUUID(), batchId, metricCode, spec.type().name(), RULE_VERSION,
                    now(), 0L, "结果聚合：科室数=" + depts.size());
        }
        jdbc.update("UPDATE ingestion_batch SET status='applied', applied_at=? WHERE id=? AND hospital_id=?",
                now(), batchId, ctx.hospitalId());
        audit.record(ctx, requestId, "应用 v3 批次", "ingestion_batch", batchId, "生成结果数=" + created);
        return detail(ctx, batchId);
    }

    private String insertResult(AuthContext ctx, Map<String, Object> definition, FormulaEngine.Spec spec,
                                FormulaEngine.Result result, String period, String dept, String batchId,
                                String versionId) {
        List<String> existing = jdbc.query(
                "SELECT id FROM metric_result WHERE hospital_id=? AND metric_code=? AND period_code=? AND dept_code=?",
                (rs, i) -> rs.getString(1), ctx.hospitalId(), spec.code(), period, dept);
        String id = existing.isEmpty() ? "res-" + UUID.randomUUID() : existing.get(0);
        if (!existing.isEmpty()) {
            jdbc.update("DELETE FROM metric_result WHERE id=?", id);
        }
        jdbc.update("INSERT INTO metric_result (id, hospital_id, definition_id, standard_version_id, metric_code,"
                        + " metric_name, unit, direction, period_code, dept_code, metric_value, numerator, denominator,"
                        + " light, target_value, warning_value, reason_code, quality_status, batch_id, formula_type,"
                        + " definition_version, computed_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, ctx.hospitalId(), definition.get("id"), versionId, spec.code(), definition.get("name"),
                spec.unit(), spec.direction(), period, dept, result.value(), result.numerator(), result.denominator(),
                result.light(), spec.target(), spec.warning(), result.reasonCode(), "validated", batchId,
                spec.type().name(), definition.get("versionNo"), now());
        return id;
    }

    private void raiseAlerts(AuthContext ctx, FormulaEngine.Result result, FormulaEngine.Spec spec, String dept,
                             String period, String metricCode, String resultId, String metricName,
                             Double previousValue) {
        List<String[]> alerts = new ArrayList<>();
        if (result.value() == null) {
            alerts.add(new String[]{"missing_data", "medium", "本期无有效值：" + result.reasonCode()});
        } else if ("red".equals(result.light())) {
            alerts.add(new String[]{"low".equals(spec.direction()) ? "over_warning" : "below_target",
                    "high", "当前值 " + result.value() + " 未达目标 " + spec.target()});
        } else if ("yellow".equals(result.light())) {
            alerts.add(new String[]{"low".equals(spec.direction()) ? "over_warning" : "below_target",
                    "medium", "当前值 " + result.value() + " 处于预警区间"});
        }
        if (result.value() != null && previousValue != null && previousValue != 0) {
            boolean high = "high".equals(spec.direction());
            boolean worse = high ? result.value() < previousValue : result.value() > previousValue;
            if (worse) {
                alerts.add(new String[]{"worsening", "medium",
                        "较上期 " + previousValue + " 恶化至 " + result.value()});
            }
            double change = Math.abs(result.value() - previousValue) / Math.abs(previousValue);
            if (change > 0.5) {
                alerts.add(new String[]{"jump", "medium",
                        "较上期 " + previousValue + " 跳变 %.0f%%".formatted(change * 100)});
            }
        }
        for (String[] alert : alerts) {
            Integer existing = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM v3_alert WHERE hospital_id=? AND result_id=? AND alert_type=? AND status<>'resolved'",
                    Integer.class, ctx.hospitalId(), resultId, alert[0]);
            if (existing != null && existing > 0) continue;
            String id = "alert-" + UUID.randomUUID();
            jdbc.update("INSERT INTO v3_alert (id, hospital_id, metric_code, metric_name, dept_code, period_code,"
                            + " alert_type, severity, status, detail, result_id, created_at, updated_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, ctx.hospitalId(), metricCode, metricName, dept, period, alert[0], alert[1], "open",
                    alert[2], resultId, now(), now());
            recordLineage(ctx.hospitalId(), "result", resultId, "alert", id, "raises");
        }
    }

    // ---------- 读 ----------

    public Map<String, Object> detail(AuthContext ctx, String batchId) {
        Map<String, Object> batch = batchRow(ctx, batchId);
        List<Map<String, Object>> issues = jdbc.query(
                "SELECT row_no, rule, field, message, severity FROM v3_quality_issue WHERE batch_id=? ORDER BY row_no",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("rowNo", rs.getInt("row_no"));
                    m.put("rule", rs.getString("rule"));
                    m.put("field", rs.getString("field"));
                    m.put("message", rs.getString("message"));
                    m.put("severity", rs.getString("severity"));
                    return m;
                }, batchId);
        List<Map<String, Object>> facts = jdbc.query(
                "SELECT metric_code, dept_code, period_code, numerator, denominator, metric_value, unit"
                        + " FROM canonical_fact WHERE batch_id=? ORDER BY metric_code, dept_code",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("metricCode", rs.getString("metric_code"));
                    m.put("deptCode", rs.getString("dept_code"));
                    m.put("period", rs.getString("period_code"));
                    m.put("numerator", rs.getObject("numerator"));
                    m.put("denominator", rs.getObject("denominator"));
                    m.put("value", rs.getObject("metric_value"));
                    m.put("unit", rs.getString("unit"));
                    return m;
                }, batchId);
        List<Map<String, Object>> traces = jdbc.query(
                "SELECT metric_code, formula_type, rule_version, executed_at, detail FROM v3_trace WHERE batch_id=?",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("metricCode", rs.getString("metric_code"));
                    m.put("formulaType", rs.getString("formula_type"));
                    m.put("ruleVersion", rs.getString("rule_version"));
                    m.put("executedAt", rs.getString("executed_at"));
                    m.put("detail", rs.getString("detail"));
                    return m;
                }, batchId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batch", batch);
        result.put("issues", issues);
        result.put("facts", facts);
        result.put("trace", traces);
        return result;
    }

    public Map<String, Object> batchRow(AuthContext ctx, String batchId) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT b.id, b.source_id, s.code AS source_code, b.standard_version_id, b.period_code, b.status,"
                        + " b.record_count, b.checksum, b.mapping_version, b.reject_reason, b.replay_of, b.raw_json,"
                        + " b.created_by, b.created_at, b.applied_at FROM ingestion_batch b"
                        + " JOIN data_source s ON s.id=b.source_id WHERE b.id=? AND b.hospital_id=?",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("sourceId", rs.getString("source_id"));
                    m.put("sourceCode", rs.getString("source_code"));
                    m.put("versionId", rs.getString("standard_version_id"));
                    m.put("period", rs.getString("period_code"));
                    m.put("status", rs.getString("status"));
                    m.put("recordCount", rs.getInt("record_count"));
                    m.put("checksum", rs.getString("checksum"));
                    m.put("mappingVersion", rs.getInt("mapping_version"));
                    m.put("rejectReason", rs.getString("reject_reason"));
                    m.put("replayOf", rs.getString("replay_of"));
                    m.put("rawJson", rs.getString("raw_json"));
                    m.put("createdBy", rs.getString("created_by"));
                    m.put("createdAt", rs.getString("created_at"));
                    m.put("appliedAt", rs.getString("applied_at"));
                    return m;
                }, batchId, ctx.hospitalId());
        if (rows.isEmpty()) throw V2Exception.notFound("批次不存在或不在可见范围");
        return rows.get(0);
    }

    public List<Map<String, Object>> listBatches(AuthContext ctx, int limit) {
        return jdbc.query(
                "SELECT b.id, s.code AS source_code, b.period_code, b.status, b.record_count, b.replay_of, b.created_at"
                        + " FROM ingestion_batch b JOIN data_source s ON s.id=b.source_id"
                        + " WHERE b.hospital_id=? ORDER BY b.created_at DESC LIMIT ?",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("sourceCode", rs.getString("source_code"));
                    m.put("period", rs.getString("period_code"));
                    m.put("status", rs.getString("status"));
                    m.put("recordCount", rs.getInt("record_count"));
                    m.put("replayOf", rs.getString("replay_of"));
                    m.put("createdAt", rs.getString("created_at"));
                    return m;
                }, ctx.hospitalId(), Math.min(Math.max(limit, 1), 100));
    }

    public void recordLineage(String hospitalId, String fromType, String fromId, String toType, String toId,
                              String relation) {
        jdbc.update("INSERT INTO lineage_edge (id, hospital_id, from_type, from_id, to_type, to_id, relation)"
                        + " VALUES (?,?,?,?,?,?,?)",
                "lin-" + UUID.randomUUID(), hospitalId, fromType, fromId, toType, toId, relation);
    }

    // ---------- 辅助 ----------

    private Map<String, Object> currentMapping(AuthContext ctx, String sourceCode) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT mapping_json, version_no FROM source_mapping WHERE hospital_id=? AND source_code=?",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("json", rs.getString("mapping_json"));
                    m.put("version", rs.getInt("version_no"));
                    return m;
                }, ctx.hospitalId(), sourceCode);
        if (rows.isEmpty()) return null;
        JsonNode root = readTree((String) rows.get(0).get("json"));
        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("fields", root.path("fields"));
        mapping.put("codeMap", root.path("codeMap"));
        mapping.put("version", rows.get(0).get("version"));
        return mapping;
    }

    private Map<String, Object> source(AuthContext ctx, String sourceCode) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT id, code FROM data_source WHERE hospital_id=? AND code=? AND active=1",
                (rs, i) -> Map.<String, Object>of("id", rs.getString("id"), "code", rs.getString("code")),
                ctx.hospitalId(), sourceCode);
        if (rows.isEmpty()) throw V2Exception.badRequest("数据源不存在或已停用：" + sourceCode);
        return rows.get(0);
    }

    private boolean departmentExists(String hospitalId, String deptCode) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM department WHERE hospital_id=? AND code=?",
                Integer.class, hospitalId, deptCode);
        return count != null && count > 0;
    }

    private boolean periodOverlaps(String hospitalId, String metricCode, String deptCode, String period) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM metric_result WHERE hospital_id=? AND metric_code=? AND dept_code=? AND period_code=?",
                Integer.class, hospitalId, metricCode, deptCode, period);
        return count != null && count > 0;
    }

    private Double previousAppliedValue(String hospitalId, String metricCode, String deptCode, String period) {
        List<Double> values = jdbc.query(
                "SELECT metric_value FROM metric_result WHERE hospital_id=? AND metric_code=? AND dept_code=?"
                        + " AND period_code<? AND metric_value IS NOT NULL ORDER BY period_code DESC",
                (rs, i) -> (Double) rs.getObject(1), hospitalId, metricCode, deptCode, period);
        return values.isEmpty() ? null : values.get(0);
    }

    private Double previousResultValue(String hospitalId, String metricCode, String deptCode, String period) {
        List<Double> values = jdbc.query(
                "SELECT metric_value FROM metric_result WHERE hospital_id=? AND metric_code=? AND dept_code=?"
                        + " AND period_code<? AND metric_value IS NOT NULL ORDER BY period_code DESC",
                (rs, i) -> (Double) rs.getObject(1), hospitalId, metricCode, deptCode, period);
        return values.isEmpty() ? null : values.get(0);
    }

    private static double scale(String unit, double numerator, double denominator) {
        double raw = numerator / denominator;
        return "%".equals(unit) ? Math.round(raw * 1000000d) / 10000d : raw;
    }

    private static JsonNode readTree(String value) {
        try {
            return value == null || value.isBlank() ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                    : new ObjectMapper().readTree(value);
        } catch (Exception e) {
            return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        }
    }

    private static String mapCode(JsonNode codeMap, String group, String raw) {
        if (raw == null) return null;
        JsonNode mapped = codeMap.path(group).path(raw);
        return mapped.isTextual() ? mapped.asText() : raw;
    }

    private static String value(JsonNode row, String field) {
        if (field == null || field.isBlank()) return null;
        JsonNode node = row.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    private static Double number(JsonNode row, String field) {
        if (field == null || field.isBlank()) return null;
        JsonNode node = row.get(field);
        if (node == null || node.isNull() || node.asText().isBlank()) return null;
        try {
            double value = Double.parseDouble(node.asText());
            return Double.isFinite(value) ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Map<String, Object> issue(int rowNo, String rule, String field, String message, String severity) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rowNo", rowNo);
        m.put("rule", rule);
        m.put("field", field);
        m.put("message", message);
        m.put("severity", severity);
        return m;
    }

    private static long countErrors(List<Map<String, Object>> issues) {
        return issues.stream().filter(i -> "error".equals(i.get("severity"))).count();
    }

    private static void validatePeriod(String period) {
        try {
            YearMonth parsed = YearMonth.parse(period);
            if (!parsed.toString().equals(period)) throw V2Exception.badRequest("period 格式须为 YYYY-MM");
            if (parsed.isBefore(YearMonth.of(2024, 1)) || parsed.isAfter(YearMonth.of(2028, 12))) {
                throw V2Exception.badRequest("period 必须在 2024-01 至 2028-12 之间");
            }
        } catch (DateTimeParseException e) {
            throw V2Exception.badRequest("period 格式须为 YYYY-MM");
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static Double toDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static String text(JsonNode body, String name) {
        JsonNode node = body.get(name);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw V2Exception.badRequest(name + " 不能为空");
        }
        return node.asText().trim();
    }

    private static String now() {
        return OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).toString();
    }

    private record Canonical(String metricCode, String deptCode, Double numerator, Double denominator,
                             Double value, String unit, int excluded) {}
}
