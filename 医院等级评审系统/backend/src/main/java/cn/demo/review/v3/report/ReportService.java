package cn.demo.review.v3.report;

import cn.demo.review.v2.V2Exception;
import cn.demo.review.v2.platform.AuditService;
import cn.demo.review.v2.security.AuthContext;
import cn.demo.review.v3.ingestion.IngestionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.YearMonth;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * v3 报告：生成不可变快照并导出 DOCX。
 * 快照保存结果、预警、整改与所用批次/定义版本，后续数据变化不改写历史报告。
 */
@Service
public class ReportService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuditService audit;
    private final IngestionService ingestion;

    public ReportService(JdbcTemplate jdbc, ObjectMapper json, AuditService audit, IngestionService ingestion) {
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
        this.ingestion = ingestion;
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> create(AuthContext ctx, JsonNode body, String requestId) {
        ctx.require("review_office");
        String versionId = text(body, "versionId");
        String from = text(body, "periodFrom");
        String to = text(body, "periodTo");
        validatePeriod(from);
        validatePeriod(to);
        if (from.compareTo(to) > 0) throw V2Exception.badRequest("periodFrom 不能晚于 periodTo");

        List<Map<String, Object>> results = jdbc.query(
                "SELECT metric_code, metric_name, dept_code, period_code, metric_value, unit, light, reason_code,"
                        + " formula_type, definition_version, batch_id FROM metric_result"
                        + " WHERE hospital_id=? AND standard_version_id=? AND period_code>=? AND period_code<=?"
                        + " ORDER BY period_code, metric_code, dept_code",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("metricCode", rs.getString("metric_code"));
                    m.put("metricName", rs.getString("metric_name"));
                    m.put("deptCode", rs.getString("dept_code"));
                    m.put("period", rs.getString("period_code"));
                    m.put("value", rs.getObject("metric_value"));
                    m.put("unit", rs.getString("unit"));
                    m.put("light", rs.getString("light"));
                    m.put("reasonCode", rs.getString("reason_code"));
                    m.put("formulaType", rs.getString("formula_type"));
                    m.put("definitionVersion", rs.getInt("definition_version"));
                    m.put("batchId", rs.getString("batch_id"));
                    return m;
                }, ctx.hospitalId(), versionId, from, to);
        if (results.isEmpty()) throw V2Exception.conflict("所选周期没有可用的指标结果");

        List<Map<String, Object>> alerts = jdbc.query(
                "SELECT id, metric_code, dept_code, period_code, alert_type, severity, status FROM v3_alert"
                        + " WHERE hospital_id=? AND period_code>=? AND period_code<=? ORDER BY created_at",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("metricCode", rs.getString("metric_code"));
                    m.put("deptCode", rs.getString("dept_code"));
                    m.put("period", rs.getString("period_code"));
                    m.put("type", rs.getString("alert_type"));
                    m.put("severity", rs.getString("severity"));
                    m.put("status", rs.getString("status"));
                    return m;
                }, ctx.hospitalId(), from, to);
        List<Map<String, Object>> improvements = jdbc.query(
                "SELECT id, alert_id, metric_code, status, review_conclusion, owner FROM v3_improvement"
                        + " WHERE hospital_id=? ORDER BY created_at",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("alertId", rs.getString("alert_id"));
                    m.put("metricCode", rs.getString("metric_code"));
                    m.put("status", rs.getString("status"));
                    m.put("conclusion", rs.getString("review_conclusion"));
                    m.put("owner", rs.getString("owner"));
                    return m;
                }, ctx.hospitalId());
        Set<String> batchIds = new LinkedHashSet<>();
        Set<String> definitionKeys = new LinkedHashSet<>();
        for (Map<String, Object> result : results) {
            if (result.get("batchId") != null) batchIds.add((String) result.get("batchId"));
            definitionKeys.add(result.get("metricCode") + "@v" + result.get("definitionVersion"));
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("versionId", versionId);
        snapshot.put("periodFrom", from);
        snapshot.put("periodTo", to);
        snapshot.put("results", results);
        snapshot.put("alerts", alerts);
        snapshot.put("improvements", improvements);
        snapshot.put("batchIds", List.copyOf(batchIds));
        snapshot.put("definitions", List.copyOf(definitionKeys));
        snapshot.put("generatedAt", now());

        String id = "rep-" + UUID.randomUUID();
        String payload;
        try {
            payload = json.writeValueAsString(snapshot);
        } catch (Exception e) {
            throw new IllegalStateException("报告快照序列化失败", e);
        }
        if (payload.length() > 15900) throw V2Exception.badRequest("所选周期数据过多，请缩小范围后重试");
        jdbc.update("INSERT INTO v3_report (id, hospital_id, standard_version_id, period_from, period_to,"
                        + " snapshot_json, created_at, created_by) VALUES (?,?,?,?,?,?,?,?)",
                id, ctx.hospitalId(), versionId, from, to, payload, now(), ctx.userId());
        for (String batchId : batchIds) {
            ingestion.recordLineage(ctx.hospitalId(), "batch", batchId, "report", id, "referenced");
        }
        audit.record(ctx, requestId, "生成 v3 报告快照", "v3_report", id,
                "周期=" + from + "~" + to + " 结果=" + results.size() + " 预警=" + alerts.size());
        return get(ctx, id);
    }

    public Map<String, Object> get(AuthContext ctx, String id) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT id, standard_version_id, period_from, period_to, snapshot_json, created_at, created_by"
                        + " FROM v3_report WHERE id=? AND hospital_id=?",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("versionId", rs.getString("standard_version_id"));
                    m.put("periodFrom", rs.getString("period_from"));
                    m.put("periodTo", rs.getString("period_to"));
                    m.put("snapshot", rs.getString("snapshot_json"));
                    m.put("createdAt", rs.getString("created_at"));
                    m.put("createdBy", rs.getString("created_by"));
                    return m;
                }, id, ctx.hospitalId());
        if (rows.isEmpty()) throw V2Exception.notFound("报告不存在或不在可见范围");
        Map<String, Object> report = rows.get(0);
        Map<String, Object> snapshot = parse((String) report.get("snapshot"));
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", report.get("id"));
        summary.put("versionId", report.get("versionId"));
        summary.put("periodFrom", report.get("periodFrom"));
        summary.put("periodTo", report.get("periodTo"));
        summary.put("createdAt", report.get("createdAt"));
        summary.put("createdBy", report.get("createdBy"));
        summary.put("metricCount", ((List<?>) snapshot.getOrDefault("results", List.of())).size());
        summary.put("alertCount", ((List<?>) snapshot.getOrDefault("alerts", List.of())).size());
        summary.put("improvementCount", ((List<?>) snapshot.getOrDefault("improvements", List.of())).size());
        summary.put("batchIds", snapshot.getOrDefault("batchIds", List.of()));
        summary.put("definitions", snapshot.getOrDefault("definitions", List.of()));
        return summary;
    }

    public List<Map<String, Object>> list(AuthContext ctx, int limit) {
        return jdbc.query("SELECT id, period_from, period_to, created_at FROM v3_report WHERE hospital_id=?"
                        + " ORDER BY created_at DESC LIMIT ?",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("periodFrom", rs.getString("period_from"));
                    m.put("periodTo", rs.getString("period_to"));
                    m.put("createdAt", rs.getString("created_at"));
                    return m;
                }, ctx.hospitalId(), Math.min(Math.max(limit, 1), 50));
    }

    public byte[] document(AuthContext ctx, String id, String requestId) throws IOException {
        List<String> rows = jdbc.query(
                "SELECT snapshot_json FROM v3_report WHERE id=? AND hospital_id=?",
                (rs, i) -> rs.getString(1), id, ctx.hospitalId());
        if (rows.isEmpty()) throw V2Exception.notFound("报告不存在或不在可见范围");
        Map<String, Object> snapshot = parse(rows.get(0));
        byte[] bytes = V3Docx.render(snapshot);
        audit.record(ctx, requestId, "导出 v3 报告", "v3_report", id, "快照 DOCX");
        return bytes;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String value) {
        try {
            return json.readValue(value, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static void validatePeriod(String period) {
        try {
            YearMonth parsed = YearMonth.parse(period);
            if (!parsed.toString().equals(period)) throw V2Exception.badRequest("周期格式须为 YYYY-MM");
        } catch (DateTimeParseException e) {
            throw V2Exception.badRequest("周期格式须为 YYYY-MM");
        }
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

    /** 快照 DOCX：只读快照内容，不重新查询当前数据。 */
    static final class V3Docx {
        private V3Docx() {}

        @SuppressWarnings("unchecked")
        static byte[] render(Map<String, Object> snapshot) throws IOException {
            List<Map<String, Object>> results = (List<Map<String, Object>>) snapshot.getOrDefault("results", List.of());
            List<Map<String, Object>> alerts = (List<Map<String, Object>>) snapshot.getOrDefault("alerts", List.of());
            List<Map<String, Object>> improvements =
                    (List<Map<String, Object>>) snapshot.getOrDefault("improvements", List.of());
            StringBuilder body = new StringBuilder();
            paragraph(body, "医院等级评审数据中台报告", true);
            paragraph(body, "演示口径 / 合成数据 / 不作正式评审结论", true);
            paragraph(body, "模拟医院：江城市第一人民医院（虚构）", false);
            paragraph(body, "周期：" + snapshot.get("periodFrom") + " — " + snapshot.get("periodTo")
                    + "；生成时间：" + snapshot.get("generatedAt"), false);
            paragraph(body, "指标结果 " + results.size() + " 条；预警 " + alerts.size() + " 条；整改 "
                    + improvements.size() + " 项", true);

            paragraph(body, "一、指标结果", true);
            StringBuilder table = new StringBuilder(
                    "<w:tbl><w:tblPr><w:tblBorders><w:top w:val=\"single\" w:sz=\"4\"/><w:left w:val=\"single\" w:sz=\"4\"/>"
                            + "<w:bottom w:val=\"single\" w:sz=\"4\"/><w:right w:val=\"single\" w:sz=\"4\"/>"
                            + "<w:insideH w:val=\"single\" w:sz=\"4\"/><w:insideV w:val=\"single\" w:sz=\"4\"/></w:tblBorders></w:tblPr>");
            row(table, "周期", "指标", "科室", "值", "灯号", "公式", "定义版本");
            for (Map<String, Object> result : results) {
                row(table, String.valueOf(result.get("period")), String.valueOf(result.get("metricName")),
                        String.valueOf(result.get("deptCode")), value(result.get("value"))
                                + String.valueOf(result.get("unit") == null ? "" : result.get("unit")),
                        String.valueOf(result.get("light")), String.valueOf(result.get("formulaType")),
                        "v" + result.get("definitionVersion"));
            }
            table.append("</w:tbl>");
            body.append(table);

            paragraph(body, "二、预警与整改", true);
            for (Map<String, Object> alert : alerts) {
                paragraph(body, "预警 " + alert.get("metricCode") + " / " + alert.get("deptCode") + " / "
                        + alert.get("type") + " / 严重度 " + alert.get("severity") + " / 状态 " + alert.get("status"), false);
            }
            for (Map<String, Object> improvement : improvements) {
                paragraph(body, "整改 " + improvement.get("metricCode") + " / 状态 " + improvement.get("status")
                        + " / 复核 " + improvement.get("conclusion") + " / 负责人 " + improvement.get("owner"), false);
            }

            paragraph(body, "三、数据来源", true);
            paragraph(body, "引用批次：" + snapshot.getOrDefault("batchIds", List.of()), false);
            paragraph(body, "引用指标定义：" + snapshot.getOrDefault("definitions", List.of()), false);
            paragraph(body, "本报告绑定生成时的快照；后续批次、整改或定义版本变化不会改变本报告内容。", false);

            String document = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
                    + body
                    + "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\" w:right=\"850\""
                    + " w:bottom=\"1134\" w:left=\"850\"/></w:sectPr></w:body></w:document>";
            try (ByteArrayOutputStream out = new ByteArrayOutputStream();
                 ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
                entry(zip, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                        + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                        + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                        + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                        + "</Types>");
                entry(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                        + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\""
                        + " Target=\"word/document.xml\"/></Relationships>");
                entry(zip, "word/document.xml", document);
                zip.finish();
                return out.toByteArray();
            }
        }

        private static void entry(ZipOutputStream zip, String name, String value) throws IOException {
            ZipEntry entry = new ZipEntry(name);
            entry.setTime(0);
            zip.putNextEntry(entry);
            zip.write(value.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        private static String xml(String value) {
            return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    .replace("\"", "&quot;").replace("'", "&apos;")
                    .replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");
        }

        private static void paragraph(StringBuilder body, String text, boolean heading) {
            body.append("<w:p><w:r><w:rPr><w:rFonts w:ascii=\"Calibri\" w:eastAsia=\"Microsoft YaHei\"/>");
            body.append(heading ? "<w:b/><w:sz w:val=\"26\"/>" : "<w:sz w:val=\"20\"/>");
            body.append("</w:rPr><w:t xml:space=\"preserve\">").append(xml(text)).append("</w:t></w:r></w:p>");
        }

        private static void row(StringBuilder table, String... cells) {
            table.append("<w:tr>");
            for (String cell : cells) {
                table.append("<w:tc><w:tcPr><w:tcW w:w=\"2000\" w:type=\"dxa\"/></w:tcPr>");
                paragraph(table, cell, false);
                table.append("</w:tc>");
            }
            table.append("</w:tr>");
        }

        private static String value(Object value) {
            if (value == null) return "—";
            if (value instanceof Number number) {
                return java.math.BigDecimal.valueOf(number.doubleValue())
                        .setScale(4, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
            }
            return String.valueOf(value);
        }
    }
}
