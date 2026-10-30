package cn.demo.review.v2.quality;

import cn.demo.review.v2.V2Exception;
import cn.demo.review.v2.platform.AuditService;
import cn.demo.review.v2.platform.Hashes;
import cn.demo.review.v2.security.AuthContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * 数据采集与质量链：DataSource → CollectionBatch → MetricFact → CalculationTrace。
 * 有 error 的批次一律 rejected 并阻断，不允许进入试算；失败批次可原样重放。
 */
@Service
public class QualityService {
    private static final YearMonth MIN_PERIOD = YearMonth.of(2025, 1);
    private static final YearMonth MAX_PERIOD = YearMonth.of(2027, 12);

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuditService audit;

    public QualityService(JdbcTemplate jdbc, ObjectMapper json, AuditService audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
    }

    public Map<String, Object> sources(AuthContext ctx) {
        List<Map<String, Object>> items = jdbc.query(
                "SELECT id, code, name, kind, active FROM data_source WHERE hospital_id=? ORDER BY code",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("code", rs.getString("code"));
                    m.put("name", rs.getString("name"));
                    m.put("kind", rs.getString("kind"));
                    m.put("active", rs.getInt("active") == 1);
                    return m;
                }, ctx.hospitalId());
        return Map.of("items", items);
    }

    @Transactional
    public Map<String, Object> createBatch(AuthContext ctx, JsonNode body, String requestId) {
        ctx.require("review_office", "indicator_admin");
        String sourceCode = text(body, "sourceCode");
        String period = text(body, "period");
        validatePeriod(period);
        List<Map<String, Object>> source = jdbc.query(
                "SELECT id FROM data_source WHERE hospital_id=? AND code=? AND active=1",
                (rs, i) -> Map.<String, Object>of("id", rs.getString("id")), ctx.hospitalId(), sourceCode);
        if (source.isEmpty()) throw V2Exception.badRequest("数据源不存在或已停用：" + sourceCode);
        String sourceId = String.valueOf(source.get(0).get("id"));

        JsonNode rowsNode = body.get("rows");
        if (rowsNode == null || !rowsNode.isArray() || rowsNode.isEmpty()) {
            throw V2Exception.badRequest("rows 必须是非空数组");
        }
        List<Row> rows = new ArrayList<>();
        for (JsonNode node : rowsNode) {
            Row row = new Row();
            row.indicatorCode = node.path("indicatorCode").asText("");
            row.numerator = node.hasNonNull("numerator") ? node.get("numerator").asDouble() : null;
            row.denominator = node.hasNonNull("denominator") ? node.get("denominator").asDouble() : null;
            row.excludedCount = node.path("excludedCount").asInt(0);
            row.value = node.hasNonNull("value") ? node.get("value").asDouble() : null;
            row.unit = node.path("unit").asText("");
            rows.add(row);
        }
        return persistBatch(ctx, sourceId, period, rows, null, requestId);
    }

    @Transactional
    public Map<String, Object> replay(AuthContext ctx, String batchId, String requestId) {
        ctx.require("review_office", "indicator_admin");
        Map<String, Object> source = batch(ctx, batchId);
        String sourceId = (String) source.get("sourceId");
        String period = (String) source.get("period");
        List<Row> rows = jdbc.query(
                "SELECT indicator_code, numerator, denominator, excluded_count, metric_value, unit"
                        + " FROM metric_fact WHERE batch_id=? ORDER BY indicator_code",
                (rs, i) -> {
                    Row row = new Row();
                    row.indicatorCode = rs.getString("indicator_code");
                    row.numerator = (Double) rs.getObject("numerator");
                    row.denominator = (Double) rs.getObject("denominator");
                    row.excludedCount = rs.getInt("excluded_count");
                    row.value = (Double) rs.getObject("metric_value");
                    row.unit = rs.getString("unit");
                    return row;
                }, batchId);
        if (rows.isEmpty()) throw V2Exception.conflict("原批次没有可重放的记录");
        return persistBatch(ctx, sourceId, period, rows, batchId, requestId);
    }

    private Map<String, Object> persistBatch(AuthContext ctx, String sourceId, String period,
                                             List<Row> rows, String replayOf, String requestId) {
        String versionId = latestPublishedVersion();
        if (versionId == null) throw V2Exception.conflict("尚无已发布的标准版本，不能导入数据批次");
        List<Map<String, Object>> definitions = jdbc.query(
                "SELECT code, unit, direction FROM indicator_definition WHERE version_id=?",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("code", rs.getString("code"));
                    m.put("unit", rs.getString("unit"));
                    m.put("direction", rs.getString("direction"));
                    return m;
                }, versionId);
        Map<String, String> unitByCode = new HashMap<>();
        for (Map<String, Object> d : definitions) unitByCode.put((String) d.get("code"), (String) d.get("unit"));

        List<Map<String, Object>> issues = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < rows.size(); index++) {
            Row row = rows.get(index);
            int rowNo = index + 1;
            if (!unitByCode.containsKey(row.indicatorCode)) {
                issues.add(issue(rowNo, "unknown_indicator", "indicatorCode", "指标不存在于当前已发布版本：" + row.indicatorCode, "error"));
                continue;
            }
            if (!seen.add(row.indicatorCode)) {
                issues.add(issue(rowNo, "duplicate_indicator", "indicatorCode", "同一批次重复指标：" + row.indicatorCode, "error"));
            }
            String unit = unitByCode.get(row.indicatorCode);
            boolean percent = "%".equals(unit);
            if (percent) {
                if (row.denominator == null || row.denominator <= 0) {
                    issues.add(issue(rowNo, "denominator_not_positive", "denominator", "比例指标分母必须大于0", "error"));
                }
                if (row.numerator != null && row.denominator != null && row.numerator > row.denominator) {
                    issues.add(issue(rowNo, "numerator_over_denominator", "numerator", "分子不能大于分母", "error"));
                }
                if (row.value == null || row.value < 0 || row.value > 100) {
                    issues.add(issue(rowNo, "percent_out_of_range", "value", "百分比指标值必须在 0 至 100 之间", "error"));
                }
            } else if (row.value == null) {
                issues.add(issue(rowNo, "missing_value", "value", "非比例指标必须给出数值", "error"));
            }
            Double previous = previousAppliedValue(ctx.hospitalId(), row.indicatorCode, period);
            if (previous != null && previous != 0 && row.value != null) {
                double change = Math.abs(row.value - previous) / Math.abs(previous);
                if (change > 0.5) {
                    issues.add(issue(rowNo, "abnormal_jump", "value",
                            "与上一期 %.4f 相比变化 %.0f%%，请确认口径或数据源".formatted(previous, change * 100), "warning"));
                }
            }
        }
        boolean rejected = issues.stream().anyMatch(i -> "error".equals(i.get("severity")));
        String batchId = "bat-" + UUID.randomUUID();
        String status = rejected ? "rejected" : "validated";
        String rejectReason = rejected ? "存在 " + issues.stream().filter(i -> "error".equals(i.get("severity"))).count()
                + " 条质量错误，批次已阻断" : "";
        String checksum = Hashes.sha256Hex(canonical(rows, period));
        jdbc.update("INSERT INTO collection_batch (id, hospital_id, source_id, period_code, status, record_count,"
                        + " checksum, reject_reason, replay_of, created_by, created_at, applied_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                batchId, ctx.hospitalId(), sourceId, period, status, rows.size(), checksum, rejectReason,
                replayOf, ctx.userId(), now(), null);
        for (int index = 0; index < rows.size(); index++) {
            Row row = rows.get(index);
            if (!unitByCode.containsKey(row.indicatorCode)) continue;
            jdbc.update("INSERT INTO metric_fact (id, batch_id, indicator_code, period_code, numerator, denominator,"
                            + " excluded_count, metric_value, unit) VALUES (?,?,?,?,?,?,?,?,?)",
                    "fact-" + UUID.randomUUID(), batchId, row.indicatorCode, period, row.numerator,
                    row.denominator, row.excludedCount, row.value, unitByCode.get(row.indicatorCode));
        }
        for (Map<String, Object> issue : issues) {
            jdbc.update("INSERT INTO quality_issue (id, batch_id, row_no, rule, field, message, severity)"
                            + " VALUES (?,?,?,?,?,?,?)",
                    "qi-" + UUID.randomUUID(), batchId, issue.get("rowNo"), issue.get("rule"),
                    issue.get("field"), issue.get("message"), issue.get("severity"));
        }
        jdbc.update("INSERT INTO calculation_trace (id, batch_id, rule_version, engine, executed_at, duration_ms, detail)"
                        + " VALUES (?,?,?,?,?,?,?)",
                "trace-" + UUID.randomUUID(), batchId, "demo-rule-v1", "synthetic-csv-adapter", now(), 0L,
                "规则：未知指标/重复/分母/分子分母关系/百分比区间/异常跳变；错误数="
                        + issues.stream().filter(i -> "error".equals(i.get("severity"))).count());
        audit.record(ctx, requestId, "导入数据批次", "collection_batch", batchId,
                "周期=" + period + " 行数=" + rows.size() + " 状态=" + status);
        return detail(ctx, batchId);
    }

    @Transactional
    public Map<String, Object> apply(AuthContext ctx, String batchId, String requestId) {
        ctx.require("review_office", "indicator_admin");
        Map<String, Object> batch = batch(ctx, batchId);
        String status = (String) batch.get("status");
        if ("rejected".equals(status)) throw V2Exception.qualityBlocked("质量不合格批次不能应用，请修正后重新导入");
        if ("applied".equals(status)) throw V2Exception.immutable("该批次已应用，不可重复应用");
        jdbc.update("UPDATE collection_batch SET status='applied', applied_at=? WHERE id=? AND hospital_id=?",
                now(), batchId, ctx.hospitalId());
        audit.record(ctx, requestId, "应用数据批次", "collection_batch", batchId, "批次状态 validated→applied");
        return detail(ctx, batchId);
    }

    public Map<String, Object> detail(AuthContext ctx, String batchId) {
        Map<String, Object> batch = batch(ctx, batchId);
        List<Map<String, Object>> facts = jdbc.query(
                "SELECT indicator_code, period_code, numerator, denominator, excluded_count, metric_value, unit"
                        + " FROM metric_fact WHERE batch_id=? ORDER BY indicator_code",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("indicatorCode", rs.getString("indicator_code"));
                    m.put("period", rs.getString("period_code"));
                    m.put("numerator", rs.getObject("numerator"));
                    m.put("denominator", rs.getObject("denominator"));
                    m.put("excludedCount", rs.getInt("excluded_count"));
                    m.put("value", rs.getObject("metric_value"));
                    m.put("unit", rs.getString("unit"));
                    return m;
                }, batchId);
        List<Map<String, Object>> issues = jdbc.query(
                "SELECT row_no, rule, field, message, severity FROM quality_issue WHERE batch_id=? ORDER BY row_no",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("rowNo", rs.getInt("row_no"));
                    m.put("rule", rs.getString("rule"));
                    m.put("field", rs.getString("field"));
                    m.put("message", rs.getString("message"));
                    m.put("severity", rs.getString("severity"));
                    return m;
                }, batchId);
        List<Map<String, Object>> trace = jdbc.query(
                "SELECT rule_version, engine, executed_at, duration_ms, detail FROM calculation_trace WHERE batch_id=?",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("ruleVersion", rs.getString("rule_version"));
                    m.put("engine", rs.getString("engine"));
                    m.put("executedAt", rs.getString("executed_at"));
                    m.put("durationMs", rs.getLong("duration_ms"));
                    m.put("detail", rs.getString("detail"));
                    return m;
                }, batchId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batch", batch);
        result.put("facts", facts);
        result.put("issues", issues);
        result.put("trace", trace);
        return result;
    }

    public Map<String, Object> batch(AuthContext ctx, String batchId) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT b.id, b.source_id, s.code AS source_code, b.period_code, b.status, b.record_count, b.checksum,"
                        + " b.reject_reason, b.replay_of, b.created_by, b.created_at, b.applied_at"
                        + " FROM collection_batch b JOIN data_source s ON s.id=b.source_id"
                        + " WHERE b.id=? AND b.hospital_id=?",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("sourceId", rs.getString("source_id"));
                    m.put("sourceCode", rs.getString("source_code"));
                    m.put("period", rs.getString("period_code"));
                    m.put("status", rs.getString("status"));
                    m.put("recordCount", rs.getInt("record_count"));
                    m.put("checksum", rs.getString("checksum"));
                    m.put("rejectReason", rs.getString("reject_reason"));
                    m.put("replayOf", rs.getString("replay_of"));
                    m.put("createdBy", rs.getString("created_by"));
                    m.put("createdAt", rs.getString("created_at"));
                    m.put("appliedAt", rs.getString("applied_at"));
                    return m;
                }, batchId, ctx.hospitalId());
        if (rows.isEmpty()) throw V2Exception.notFound("批次不存在或不在可见范围");
        return rows.get(0);
    }

    public List<String> appliedBatchIds(String hospitalId) {
        return jdbc.query("SELECT id FROM collection_batch WHERE hospital_id=? AND status='applied' ORDER BY created_at",
                (rs, i) -> rs.getString(1), hospitalId);
    }

    /** 批次列表：只返回本院数据，按创建时间倒序。 */
    public Map<String, Object> list(AuthContext ctx, int limit) {
        List<Map<String, Object>> items = jdbc.query(
                "SELECT b.id, s.code AS source_code, b.period_code, b.status, b.record_count, b.reject_reason,"
                        + " b.replay_of, b.created_at, b.applied_at FROM collection_batch b"
                        + " JOIN data_source s ON s.id=b.source_id WHERE b.hospital_id=?"
                        + " ORDER BY b.created_at DESC LIMIT ?",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("sourceCode", rs.getString("source_code"));
                    m.put("period", rs.getString("period_code"));
                    m.put("status", rs.getString("status"));
                    m.put("recordCount", rs.getInt("record_count"));
                    m.put("rejectReason", rs.getString("reject_reason"));
                    m.put("replayOf", rs.getString("replay_of"));
                    m.put("createdAt", rs.getString("created_at"));
                    m.put("appliedAt", rs.getString("applied_at"));
                    return m;
                }, ctx.hospitalId(), Math.min(Math.max(limit, 1), 100));
        return Map.of("items", items);
    }

    private String latestPublishedVersion() {
        List<String> ids = jdbc.query(
                "SELECT id FROM standard_version WHERE status='published' ORDER BY created_at DESC",
                (rs, i) -> rs.getString(1));
        return ids.isEmpty() ? null : ids.get(0);
    }

    private Double previousAppliedValue(String hospitalId, String indicatorCode, String period) {
        List<Double> values = jdbc.query(
                "SELECT f.metric_value FROM metric_fact f JOIN collection_batch b ON b.id=f.batch_id"
                        + " WHERE b.hospital_id=? AND b.status='applied' AND f.indicator_code=? AND f.period_code<?"
                        + " ORDER BY f.period_code DESC",
                (rs, i) -> (Double) rs.getObject(1), hospitalId, indicatorCode, period);
        return values.isEmpty() ? null : values.get(0);
    }

    private static String canonical(List<Row> rows, String period) {
        StringBuilder out = new StringBuilder(period);
        for (Row row : rows) {
            out.append('|').append(row.indicatorCode).append('=')
                    .append(row.numerator).append('/').append(row.denominator)
                    .append('@').append(row.value);
        }
        return out.toString();
    }

    private static void validatePeriod(String period) {
        try {
            YearMonth parsed = YearMonth.parse(period);
            if (!parsed.toString().equals(period)) throw V2Exception.badRequest("period 格式须为 YYYY-MM");
            if (parsed.isBefore(MIN_PERIOD) || parsed.isAfter(MAX_PERIOD)) {
                throw V2Exception.badRequest("period 必须在 2025-01 至 2027-12 之间");
            }
        } catch (DateTimeParseException e) {
            throw V2Exception.badRequest("period 格式须为 YYYY-MM");
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

    private static final class Row {
        String indicatorCode;
        Double numerator;
        Double denominator;
        Integer excludedCount;
        Double value;
        String unit;
    }
}
