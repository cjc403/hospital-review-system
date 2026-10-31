package cn.demo.review.v3.alert;

import cn.demo.review.v2.V2Exception;
import cn.demo.review.v2.platform.AuditService;
import cn.demo.review.v2.security.AuthContext;
import cn.demo.review.v3.ingestion.IngestionService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * 预警与 PDCA：预警状态机、整改项目、复核结论。
 * 状态变更全部审计；终态不可再改；跨科室访问由服务端拒绝。
 */
@Service
public class AlertService {
    private static final Set<String> CONCLUSIONS = Set.of(
            "improved", "partially_improved", "not_recovered", "invalidated");
    private static final Set<String> TERMINAL = Set.of("resolved", "ignored");

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final IngestionService ingestion;

    public AlertService(JdbcTemplate jdbc, AuditService audit, IngestionService ingestion) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.ingestion = ingestion;
    }

    public Map<String, Object> listAlerts(AuthContext ctx, String status, String severity, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, metric_code, metric_name, dept_code, period_code, alert_type, severity, status,"
                        + " detail, result_id, assignee, due_date, created_at, updated_at FROM v3_alert WHERE hospital_id=?");
        List<Object> args = new ArrayList<>();
        args.add(ctx.hospitalId());
        if (ctx.deptCode() != null) {
            sql.append(" AND dept_code=?");
            args.add(ctx.deptCode());
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status=?");
            args.add(status);
        }
        if (severity != null && !severity.isBlank()) {
            sql.append(" AND severity=?");
            args.add(severity);
        }
        sql.append(" ORDER BY created_at DESC LIMIT ?");
        args.add(Math.min(Math.max(limit, 1), 200));
        List<Map<String, Object>> items = jdbc.query(sql.toString(), (rs, i) -> alertRow(rs), args.toArray());
        return Map.of("items", items);
    }

    @Transactional
    public Map<String, Object> transition(AuthContext ctx, String alertId, String action,
                                          JsonNode body, String requestId) {
        ctx.require("review_office", "dept_head");
        Map<String, Object> alert = alertRow(ctx, alertId);
        String current = String.valueOf(alert.get("status"));
        if (TERMINAL.contains(current)) throw V2Exception.immutable("预警已结束，不能再变更");
        String dept = (String) alert.get("deptCode");
        if (ctx.deptCode() != null) {
            if (!ctx.deptCode().equals(dept)) throw V2Exception.notFound("预警不存在或不在可见范围");
            if (!"ack".equals(action)) throw V2Exception.forbidden("科室只可确认本科室预警");
        }
        String next = switch (action) {
            case "ack" -> "acknowledged";
            case "assign" -> "assigned";
            case "resolve" -> "resolved";
            case "ignore" -> "ignored";
            default -> throw V2Exception.badRequest("未知预警动作：" + action);
        };
        boolean allowed = switch (current) {
            case "open" -> "acknowledged".equals(next) || "ignored".equals(next);
            case "acknowledged" -> "assigned".equals(next) || "ignored".equals(next);
            case "assigned" -> "resolved".equals(next) || "ignored".equals(next);
            default -> false;
        };
        if (!allowed) throw new V2Exception(409, "invalid_transition", "不允许从 " + current + " 变为 " + next);
        String assignee = body == null ? null : body.path("assignee").asText(null);
        String dueDate = body == null ? null : body.path("dueDate").asText(null);
        if ("assigned".equals(next) && (assignee == null || assignee.isBlank())) {
            throw V2Exception.badRequest("分派必须填写 assignee");
        }
        if (dueDate != null && !dueDate.isBlank()) {
            try {
                LocalDate.parse(dueDate);
            } catch (Exception e) {
                throw V2Exception.badRequest("dueDate 格式须为 YYYY-MM-DD");
            }
        }
        String note = body == null ? "" : body.path("note").asText("");
        jdbc.update("UPDATE v3_alert SET status=?, assignee=COALESCE(?, assignee), due_date=COALESCE(?, due_date),"
                        + " detail=?, updated_at=? WHERE id=? AND hospital_id=?",
                next, assignee, dueDate, append(alert.get("detail"), note), now(), alertId, ctx.hospitalId());
        audit.record(ctx, requestId, "预警" + action, "v3_alert", alertId, current + "→" + next
                + (note.isBlank() ? "" : "；说明=" + note));
        return alertRow(ctx, alertId);
    }

    @Transactional
    public Map<String, Object> createFromAlert(AuthContext ctx, JsonNode body, String requestId) {
        ctx.require("review_office");
        String alertId = text(body, "alertId");
        Map<String, Object> alert = alertRow(ctx, alertId);
        String improvementId = "imp-" + UUID.randomUUID();
        jdbc.update("INSERT INTO v3_improvement (id, hospital_id, alert_id, metric_code, metric_name, root_cause,"
                        + " action_text, owner, due_date, status, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                improvementId, ctx.hospitalId(), alertId, alert.get("metricCode"), alert.get("metricName"),
                text(body, "rootCause"), text(body, "action"), text(body, "owner"),
                body.path("dueDate").asText(""), "open", now(), now());
        if (!"assigned".equals(alert.get("status")) && !TERMINAL.contains(String.valueOf(alert.get("status")))) {
            jdbc.update("UPDATE v3_alert SET status='assigned', updated_at=? WHERE id=? AND hospital_id=?",
                    now(), alertId, ctx.hospitalId());
        }
        ingestion.recordLineage(ctx.hospitalId(), "alert", alertId, "improvement", improvementId, "escalates");
        audit.record(ctx, requestId, "由预警创建整改", "v3_improvement", improvementId, "预警=" + alertId);
        return improvementRow(ctx, improvementId);
    }

    public Map<String, Object> listImprovements(AuthContext ctx, String status) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, alert_id, metric_code, metric_name, root_cause, action_text, owner, due_date, status,"
                        + " review_conclusion, review_note, evidence_item_id, closed_at, created_at, updated_at"
                        + " FROM v3_improvement WHERE hospital_id=?");
        List<Object> args = new ArrayList<>();
        args.add(ctx.hospitalId());
        if (status != null && !status.isBlank()) {
            sql.append(" AND status=?");
            args.add(status);
        }
        sql.append(" ORDER BY created_at DESC LIMIT 100");
        List<Map<String, Object>> items = jdbc.query(sql.toString(), (rs, i) -> improvementMap(rs), args.toArray());
        if (ctx.deptCode() != null) {
            items = items.stream().filter(item -> ctx.deptCode().equals(deptOfAlert(ctx, (String) item.get("alertId"))))
                    .toList();
        }
        return Map.of("items", items);
    }

    public Map<String, Object> improvement(AuthContext ctx, String id) {
        return improvementRow(ctx, id);
    }

    @Transactional
    public Map<String, Object> reviewImprovement(AuthContext ctx, String id, JsonNode body, String requestId) {
        ctx.require("review_office");
        Map<String, Object> improvement = improvementRow(ctx, id);
        if ("closed".equals(improvement.get("status"))) throw V2Exception.immutable("整改已关闭，不能重复复核");
        String conclusion = text(body, "conclusion");
        if (!CONCLUSIONS.contains(conclusion)) throw V2Exception.badRequest("复核结论取值不合法");
        String evidenceItemId = body.path("evidenceItemId").asText(null);
        if (evidenceItemId != null && !evidenceItemId.isBlank()) {
            Integer exists = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM evidence_item WHERE id=? AND hospital_id=?",
                    Integer.class, evidenceItemId, ctx.hospitalId());
            if (exists == null || exists == 0) throw V2Exception.badRequest("佐证材料不存在于本院");
        }
        jdbc.update("UPDATE v3_improvement SET status='closed', review_conclusion=?, review_note=?,"
                        + " evidence_item_id=?, closed_at=?, updated_at=? WHERE id=? AND hospital_id=?",
                conclusion, body.path("note").asText(""), evidenceItemId, now(), now(), id, ctx.hospitalId());
        if (evidenceItemId != null && !evidenceItemId.isBlank()) {
            ingestion.recordLineage(ctx.hospitalId(), "improvement", id, "evidence", evidenceItemId, "proves");
        }
        audit.record(ctx, requestId, "整改复核", "v3_improvement", id, "结论=" + conclusion);
        return improvementRow(ctx, id);
    }

    // ---------- 辅助 ----------

    private Map<String, Object> alertRow(AuthContext ctx, String id) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT id, metric_code, metric_name, dept_code, period_code, alert_type, severity, status,"
                        + " detail, result_id, assignee, due_date, created_at, updated_at FROM v3_alert"
                        + " WHERE id=? AND hospital_id=?",
                (rs, i) -> alertRow(rs), id, ctx.hospitalId());
        if (rows.isEmpty()) throw V2Exception.notFound("预警不存在或不在可见范围");
        return rows.get(0);
    }

    private Map<String, Object> improvementRow(AuthContext ctx, String id) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT id, alert_id, metric_code, metric_name, root_cause, action_text, owner, due_date, status,"
                        + " review_conclusion, review_note, evidence_item_id, closed_at, created_at, updated_at"
                        + " FROM v3_improvement WHERE id=? AND hospital_id=?",
                (rs, i) -> improvementMap(rs), id, ctx.hospitalId());
        if (rows.isEmpty()) throw V2Exception.notFound("整改不存在或不在可见范围");
        return rows.get(0);
    }

    private String deptOfAlert(AuthContext ctx, String alertId) {
        if (alertId == null) return null;
        List<String> depts = jdbc.query("SELECT dept_code FROM v3_alert WHERE id=? AND hospital_id=?",
                (rs, i) -> rs.getString(1), alertId, ctx.hospitalId());
        return depts.isEmpty() ? null : depts.get(0);
    }

    private static Map<String, Object> alertRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getString("id"));
        m.put("metricCode", rs.getString("metric_code"));
        m.put("metricName", rs.getString("metric_name"));
        m.put("deptCode", rs.getString("dept_code"));
        m.put("period", rs.getString("period_code"));
        m.put("type", rs.getString("alert_type"));
        m.put("severity", rs.getString("severity"));
        m.put("status", rs.getString("status"));
        m.put("detail", rs.getString("detail"));
        m.put("resultId", rs.getString("result_id"));
        m.put("assignee", rs.getString("assignee"));
        m.put("dueDate", rs.getString("due_date"));
        m.put("createdAt", rs.getString("created_at"));
        m.put("updatedAt", rs.getString("updated_at"));
        return m;
    }

    private static Map<String, Object> improvementMap(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getString("id"));
        m.put("alertId", rs.getString("alert_id"));
        m.put("metricCode", rs.getString("metric_code"));
        m.put("metricName", rs.getString("metric_name"));
        m.put("rootCause", rs.getString("root_cause"));
        m.put("action", rs.getString("action_text"));
        m.put("owner", rs.getString("owner"));
        m.put("dueDate", rs.getString("due_date"));
        m.put("status", rs.getString("status"));
        m.put("reviewConclusion", rs.getString("review_conclusion"));
        m.put("reviewNote", rs.getString("review_note"));
        m.put("evidenceItemId", rs.getString("evidence_item_id"));
        m.put("closedAt", rs.getString("closed_at"));
        m.put("createdAt", rs.getString("created_at"));
        m.put("updatedAt", rs.getString("updated_at"));
        return m;
    }

    private static String append(Object current, String note) {
        if (note == null || note.isBlank()) return current == null ? "" : String.valueOf(current);
        String base = current == null ? "" : String.valueOf(current);
        String merged = base.isBlank() ? note : base + "；" + note;
        return merged.length() > 1000 ? merged.substring(0, 1000) : merged;
    }

    private static String text(JsonNode body, String name) {
        JsonNode node = body == null ? null : body.get(name);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw V2Exception.badRequest(name + " 不能为空");
        }
        return node.asText().trim();
    }

    private static String now() {
        return OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).toString();
    }
}
