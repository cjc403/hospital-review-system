package cn.demo.review.v3.result;

import cn.demo.review.v2.V2Exception;
import cn.demo.review.v2.security.AuthContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 指标结果读取、下钻与血缘。
 * 所有查询按医院过滤；科室负责人只能看到本科室结果，领导与观察员看不到成员明细。
 */
@Service
public class ResultService {
    private final JdbcTemplate jdbc;

    public ResultService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Map<String, Object> list(AuthContext ctx, String metricCode, String period, String deptCode,
                                    String versionId, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, metric_code, metric_name, unit, direction, period_code, dept_code, metric_value,"
                        + " numerator, denominator, light, target_value, warning_value, reason_code, quality_status,"
                        + " batch_id, definition_id, definition_version, formula_type, computed_at"
                        + " FROM metric_result WHERE hospital_id=?");
        List<Object> args = new ArrayList<>();
        args.add(ctx.hospitalId());
        if (ctx.deptCode() != null) {
            sql.append(" AND dept_code=?");
            args.add(ctx.deptCode());
        } else if (deptCode != null && !deptCode.isBlank()) {
            sql.append(" AND dept_code=?");
            args.add(deptCode);
        }
        if (metricCode != null && !metricCode.isBlank()) {
            sql.append(" AND metric_code=?");
            args.add(metricCode);
        }
        if (period != null && !period.isBlank()) {
            sql.append(" AND period_code=?");
            args.add(period);
        }
        if (versionId != null && !versionId.isBlank()) {
            sql.append(" AND standard_version_id=?");
            args.add(versionId);
        }
        sql.append(" ORDER BY period_code DESC, metric_code, dept_code LIMIT ?");
        args.add(Math.min(Math.max(limit, 1), 200));
        List<Map<String, Object>> items = jdbc.query(sql.toString(), (rs, i) -> row(rs), args.toArray());
        return Map.of("items", items);
    }

    public Map<String, Object> get(AuthContext ctx, String id) {
        return resultRow(ctx, id);
    }

    public Map<String, Object> drilldown(AuthContext ctx, String id) {
        Map<String, Object> result = resultRow(ctx, id);
        List<Map<String, Object>> departments = jdbc.query(
                "SELECT dept_code, metric_value, numerator, denominator, light FROM metric_result"
                        + " WHERE hospital_id=? AND metric_code=? AND period_code=? AND dept_code<>'ALL' ORDER BY dept_code",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("deptCode", rs.getString("dept_code"));
                    m.put("value", rs.getObject("metric_value"));
                    m.put("numerator", rs.getObject("numerator"));
                    m.put("denominator", rs.getObject("denominator"));
                    m.put("light", rs.getString("light"));
                    return m;
                }, ctx.hospitalId(), result.get("metricCode"), result.get("period"));
        Map<String, Object> members = new LinkedHashMap<>();
        members.put("masked", true);
        members.put("items", List.of());
        if (ctx.has("review_office") || ctx.has("indicator_admin")) {
            members.put("note", "v3 只提供科室级下钻；患者级明细需在真实接入后按授权开列，本演示不生成真实病例");
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("result", result);
        response.put("departments", departments);
        response.put("members", members);
        return response;
    }

    /** 血缘：结果 → 定义/批次/来源，以及下游预警与整改。 */
    public Map<String, Object> lineage(AuthContext ctx, String resultId) {
        Map<String, Object> result = resultRow(ctx, resultId);
        String batchId = (String) result.get("batchId");
        String definitionId = (String) result.get("definitionId");
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        node(nodes, "result", resultId, result.get("metricName") + " " + result.get("period")
                + " " + result.get("deptCode"));
        if (definitionId != null) {
            node(nodes, "definition", definitionId, result.get("metricCode") + " 定义 v" + result.get("definitionVersion"));
            edge(edges, "definition", definitionId, "result", resultId, "defines");
        }
        if (batchId != null) {
            node(nodes, "batch", batchId, "采集批次");
            edge(edges, "batch", batchId, "result", resultId, "produces");
            List<Map<String, Object>> sources = jdbc.query(
                    "SELECT s.id, s.code, s.name FROM ingestion_batch b JOIN data_source s ON s.id=b.source_id"
                            + " WHERE b.id=?", (rs, i) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", rs.getString("id"));
                        m.put("code", rs.getString("code"));
                        m.put("name", rs.getString("name"));
                        return m;
                    }, batchId);
            if (!sources.isEmpty()) {
                Map<String, Object> source = sources.get(0);
                node(nodes, "source", (String) source.get("id"), source.get("name") + "（" + source.get("code") + "）");
                edge(edges, "source", (String) source.get("id"), "batch", batchId, "feeds");
            }
            Integer facts = jdbc.queryForObject("SELECT COUNT(*) FROM canonical_fact WHERE batch_id=?",
                    Integer.class, batchId);
            node(nodes, "facts", batchId + "-facts", "标准事实 " + (facts == null ? 0 : facts) + " 条");
            edge(edges, "batch", batchId, "facts", batchId + "-facts", "normalizes");
        }
        jdbc.query("SELECT id, alert_type, status FROM v3_alert WHERE hospital_id=? AND result_id=?",
                rs -> {
                    String alertId = rs.getString("id");
                    node(nodes, "alert", alertId, rs.getString("alert_type") + " / " + rs.getString("status"));
                    edge(edges, "result", resultId, "alert", alertId, "raises");
                    List<Map<String, Object>> improvements = jdbc.query(
                            "SELECT id, status FROM v3_improvement WHERE hospital_id=? AND alert_id=?",
                            (rs2, i) -> Map.<String, Object>of("id", rs2.getString("id"), "status", rs2.getString("status")),
                            ctx.hospitalId(), alertId);
                    for (Map<String, Object> improvement : improvements) {
                        String improvementId = (String) improvement.get("id");
                        node(nodes, "improvement", improvementId, "整改 " + improvement.get("status"));
                        edge(edges, "alert", alertId, "improvement", improvementId, "escalates");
                    }
                }, ctx.hospitalId(), resultId);
        return Map.of("nodes", nodes, "edges", edges);
    }

    private Map<String, Object> resultRow(AuthContext ctx, String id) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT id, metric_code, metric_name, unit, direction, period_code, dept_code, metric_value,"
                        + " numerator, denominator, light, target_value, warning_value, reason_code, quality_status,"
                        + " batch_id, definition_id, definition_version, formula_type, computed_at"
                        + " FROM metric_result WHERE id=? AND hospital_id=?",
                (rs, i) -> row(rs), id, ctx.hospitalId());
        if (rows.isEmpty()) throw V2Exception.notFound("指标结果不存在或不在可见范围");
        Map<String, Object> result = rows.get(0);
        if (ctx.deptCode() != null && !ctx.deptCode().equals(result.get("deptCode"))) {
            throw V2Exception.notFound("指标结果不存在或不在可见范围");
        }
        return result;
    }

    private static Map<String, Object> row(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getString("id"));
        m.put("metricCode", rs.getString("metric_code"));
        m.put("metricName", rs.getString("metric_name"));
        m.put("unit", rs.getString("unit"));
        m.put("direction", rs.getString("direction"));
        m.put("period", rs.getString("period_code"));
        m.put("deptCode", rs.getString("dept_code"));
        m.put("value", rs.getObject("metric_value"));
        m.put("numerator", rs.getObject("numerator"));
        m.put("denominator", rs.getObject("denominator"));
        m.put("light", rs.getString("light"));
        m.put("target", rs.getObject("target_value"));
        m.put("warning", rs.getObject("warning_value"));
        m.put("reasonCode", rs.getString("reason_code"));
        m.put("qualityStatus", rs.getString("quality_status"));
        m.put("batchId", rs.getString("batch_id"));
        m.put("definitionId", rs.getString("definition_id"));
        m.put("definitionVersion", rs.getInt("definition_version"));
        m.put("formulaType", rs.getString("formula_type"));
        m.put("computedAt", rs.getString("computed_at"));
        return m;
    }

    private static void node(List<Map<String, Object>> nodes, String type, String id, Object label) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("id", id);
        m.put("label", label);
        nodes.add(m);
    }

    private static void edge(List<Map<String, Object>> edges, String fromType, String fromId,
                             String toType, String toId, String relation) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", fromId);
        m.put("to", toId);
        m.put("relation", relation);
        edges.add(m);
    }
}
