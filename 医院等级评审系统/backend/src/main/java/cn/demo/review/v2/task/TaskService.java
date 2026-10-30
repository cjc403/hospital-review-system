package cn.demo.review.v2.task;

import cn.demo.review.v2.V2Exception;
import cn.demo.review.v2.platform.AuditService;
import cn.demo.review.v2.standard.StandardService;
import cn.demo.review.v2.security.AuthContext;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * 条款任务：分派、状态机、逾期判定、幂等与审计。
 * 所有读写都带医院范围；科室负责人只能看到并推进本科室任务。
 */
@Service
public class TaskService {
    private static final Set<String> PRIORITIES = Set.of("high", "normal", "low");
    private static final Set<String> TERMINAL = Set.of("accepted", "cancelled");

    private final JdbcTemplate jdbc;
    private final StandardService standards;
    private final AuditService audit;

    public TaskService(JdbcTemplate jdbc, StandardService standards, AuditService audit) {
        this.jdbc = jdbc;
        this.standards = standards;
        this.audit = audit;
    }

    public Map<String, Object> list(AuthContext ctx, String dept, String status, boolean overdueOnly) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, hospital_id, version_id, clause_code, dept_code, title, assignee_post, priority,"
                        + " due_date, status, return_reason, created_by, created_at, updated_at"
                        + " FROM review_task WHERE hospital_id=?");
        List<Object> args = new ArrayList<>();
        args.add(ctx.hospitalId());
        if (ctx.deptCode() != null) {
            sql.append(" AND dept_code=?");
            args.add(ctx.deptCode());
        } else if (dept != null && !dept.isBlank()) {
            sql.append(" AND dept_code=?");
            args.add(dept);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status=?");
            args.add(status);
        }
        sql.append(" ORDER BY due_date, created_at");
        String today = LocalDate.now().toString();
        List<Map<String, Object>> items = jdbc.query(sql.toString(), (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            String taskStatus = rs.getString("status");
            String dueDate = rs.getString("due_date");
            boolean overdue = dueDate != null && dueDate.compareTo(today) < 0 && !TERMINAL.contains(taskStatus);
            m.put("id", rs.getString("id"));
            m.put("hospitalId", rs.getString("hospital_id"));
            m.put("versionId", rs.getString("version_id"));
            m.put("clauseCode", rs.getString("clause_code"));
            m.put("deptCode", rs.getString("dept_code"));
            m.put("title", rs.getString("title"));
            m.put("assigneePost", rs.getString("assignee_post"));
            m.put("priority", rs.getString("priority"));
            m.put("dueDate", dueDate);
            m.put("status", taskStatus);
            m.put("returnReason", rs.getString("return_reason"));
            m.put("overdue", overdue);
            m.put("createdBy", rs.getString("created_by"));
            m.put("createdAt", rs.getString("created_at"));
            m.put("updatedAt", rs.getString("updated_at"));
            return m;
        }, args.toArray());
        if (overdueOnly) items = items.stream().filter(m -> Boolean.TRUE.equals(m.get("overdue"))).toList();
        return Map.of("items", items, "summary", summaryOf(items));
    }

    private Map<String, Object> summaryOf(List<Map<String, Object>> items) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", items.size());
        for (String s : List.of("todo", "in_progress", "submitted", "returned", "accepted", "cancelled")) {
            summary.put(s, items.stream().filter(m -> s.equals(m.get("status"))).count());
        }
        summary.put("overdue", items.stream().filter(m -> Boolean.TRUE.equals(m.get("overdue"))).count());
        return summary;
    }

    @Transactional
    public Map<String, Object> assign(AuthContext ctx, JsonNode body, String requestId) {
        ctx.require("review_office");
        String versionId = text(body, "versionId");
        String deptCode = text(body, "deptCode");
        String requestKey = text(body, "requestKey");
        if (requestKey.length() > 120) throw V2Exception.badRequest("requestKey 过长");
        String assigneePost = optional(body, "assigneePost", "科室质控员");
        String priority = optional(body, "priority", "normal");
        if (!PRIORITIES.contains(priority)) throw V2Exception.badRequest("priority 只能是 high、normal 或 low");
        String dueDate = text(body, "dueDate");
        parseDate(dueDate);

        List<Map<String, Object>> existing = tasksByRequestKey(ctx.hospitalId(), requestKey);
        if (!existing.isEmpty()) {
            return Map.of("tasks", existing, "idempotent", true);
        }
        try {
            jdbc.update("INSERT INTO assignment_request (hospital_id, request_key, task_count, created_at) VALUES (?,?,?,?)",
                    ctx.hospitalId(), requestKey, 0, LocalDate.now().toString());
        } catch (org.springframework.dao.DataIntegrityViolationException duplicate) {
            // 并发重复请求：以先写入的那次为准，返回原回执
            return Map.of("tasks", tasksByRequestKey(ctx.hospitalId(), requestKey), "idempotent", true);
        }

        standards.requirePublished(versionId);
        Integer deptExists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM department WHERE hospital_id=? AND code=?", Integer.class, ctx.hospitalId(), deptCode);
        if (deptExists == null || deptExists == 0) throw V2Exception.badRequest("科室不存在：" + deptCode);

        JsonNode codesNode = body.get("clauseCodes");
        if (codesNode == null || !codesNode.isArray() || codesNode.isEmpty()) {
            throw V2Exception.badRequest("clauseCodes 必须是非空数组");
        }
        List<String> created = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        String now = LocalDate.now().toString();
        for (JsonNode node : codesNode) {
            String clauseCode = node.asText();
            List<Map<String, Object>> clauses = jdbc.query(
                    "SELECT title FROM standard_clause WHERE version_id=? AND code=?",
                    (rs, i) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("title", rs.getString("title"));
                        return m;
                    }, versionId, clauseCode);
            if (clauses.isEmpty()) {
                unknown.add(clauseCode);
                continue;
            }
            String id = "task-" + UUID.randomUUID();
            jdbc.update("INSERT INTO review_task (id, hospital_id, version_id, clause_code, dept_code, title,"
                            + " assignee_post, priority, due_date, status, return_reason, request_key, created_by,"
                            + " created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, ctx.hospitalId(), versionId, clauseCode, deptCode, String.valueOf(clauses.get(0).get("title")),
                    assigneePost, priority, dueDate, "todo", "", requestKey, ctx.userId(), now, now);
            created.add(id);
        }
        if (!unknown.isEmpty()) {
            throw V2Exception.badRequest("条款不存在于该版本：" + String.join(",", unknown));
        }
        audit.record(ctx, requestId, "分派条款任务", "review_task", requestKey,
                "科室=" + deptCode + " 条款数=" + created.size() + " 截止=" + dueDate);
        return Map.of("tasks", tasksByRequestKey(ctx.hospitalId(), requestKey), "idempotent", false);
    }

    @Transactional
    public Map<String, Object> update(AuthContext ctx, String taskId, JsonNode body, String requestId) {
        Map<String, Object> task = task(ctx.hospitalId(), taskId);
        String current = (String) task.get("status");
        if (TERMINAL.contains(current)) throw V2Exception.immutable("终态任务不可再变更，如需继续请重新分派");
        String next = text(body, "status");
        String comment = optional(body, "comment", "");
        String dept = (String) task.get("deptCode");

        if (ctx.has("review_office")) {
            // 评审办可执行全部合法流转
        } else if (ctx.has("dept_head")) {
            ctx.requireDept(dept);
            Set<String> allowedForDept = Set.of("todo|in_progress", "returned|in_progress", "in_progress|submitted");
            if (!allowedForDept.contains(current + "|" + next)) {
                throw V2Exception.forbidden("科室负责人只能推进本科室任务，且不能审核或退回");
            }
        } else {
            throw V2Exception.forbidden("当前角色不能变更任务状态");
        }

        Map<String, Set<String>> transitions = Map.of(
                "todo", Set.of("in_progress", "cancelled"),
                "in_progress", Set.of("submitted", "cancelled"),
                "submitted", Set.of("accepted", "returned"),
                "returned", Set.of("in_progress"));
        Set<String> allowed = transitions.getOrDefault(current, Set.of());
        if (!allowed.contains(next)) {
            throw V2Exception.conflict("不允许从 " + current + " 直接变为 " + next);
        }
        if ("returned".equals(next) && comment.isBlank()) {
            throw V2Exception.badRequest("退回必须填写原因");
        }
        String now = LocalDate.now().toString();
        jdbc.update("UPDATE review_task SET status=?, return_reason=?, updated_at=? WHERE id=? AND hospital_id=?",
                next, "returned".equals(next) ? comment : task.get("returnReason"), now, taskId, ctx.hospitalId());
        audit.record(ctx, requestId, "变更任务状态", "review_task", taskId,
                current + "→" + next + (comment.isBlank() ? "" : "；说明=" + comment));
        return task(ctx.hospitalId(), taskId);
    }

    public Map<String, Object> task(String hospitalId, String taskId) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT id, hospital_id, version_id, clause_code, dept_code, title, assignee_post, priority,"
                        + " due_date, status, return_reason, created_by, created_at, updated_at"
                        + " FROM review_task WHERE id=? AND hospital_id=?",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    String status = rs.getString("status");
                    String dueDate = rs.getString("due_date");
                    m.put("id", rs.getString("id"));
                    m.put("hospitalId", rs.getString("hospital_id"));
                    m.put("versionId", rs.getString("version_id"));
                    m.put("clauseCode", rs.getString("clause_code"));
                    m.put("deptCode", rs.getString("dept_code"));
                    m.put("title", rs.getString("title"));
                    m.put("assigneePost", rs.getString("assignee_post"));
                    m.put("priority", rs.getString("priority"));
                    m.put("dueDate", dueDate);
                    m.put("status", status);
                    m.put("returnReason", rs.getString("return_reason"));
                    m.put("overdue", dueDate != null && dueDate.compareTo(LocalDate.now().toString()) < 0
                            && !TERMINAL.contains(status));
                    m.put("createdBy", rs.getString("created_by"));
                    m.put("createdAt", rs.getString("created_at"));
                    m.put("updatedAt", rs.getString("updated_at"));
                    return m;
                }, taskId, hospitalId);
        if (rows.isEmpty()) throw V2Exception.notFound("任务不存在或不在可见范围");
        return rows.get(0);
    }

    private List<Map<String, Object>> tasksByRequestKey(String hospitalId, String requestKey) {
        return jdbc.query("SELECT id, hospital_id, version_id, clause_code, dept_code, title, assignee_post, priority,"
                        + " due_date, status, return_reason, created_by, created_at, updated_at"
                        + " FROM review_task WHERE hospital_id=? AND request_key=? ORDER BY created_at",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("hospitalId", rs.getString("hospital_id"));
                    m.put("versionId", rs.getString("version_id"));
                    m.put("clauseCode", rs.getString("clause_code"));
                    m.put("deptCode", rs.getString("dept_code"));
                    m.put("title", rs.getString("title"));
                    m.put("assigneePost", rs.getString("assignee_post"));
                    m.put("priority", rs.getString("priority"));
                    m.put("dueDate", rs.getString("due_date"));
                    m.put("status", rs.getString("status"));
                    m.put("returnReason", rs.getString("return_reason"));
                    m.put("overdue", false);
                    m.put("createdBy", rs.getString("created_by"));
                    m.put("createdAt", rs.getString("created_at"));
                    m.put("updatedAt", rs.getString("updated_at"));
                    return m;
                }, hospitalId, requestKey);
    }

    private static void parseDate(String value) {
        try {
            LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw V2Exception.badRequest("dueDate 格式须为 YYYY-MM-DD");
        }
    }

    private static String text(JsonNode body, String name) {
        JsonNode node = body.get(name);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw V2Exception.badRequest(name + " 不能为空");
        }
        return node.asText().trim();
    }

    private static String optional(JsonNode body, String name, String fallback) {
        JsonNode node = body.get(name);
        if (node == null || !node.isTextual()) return fallback;
        return node.asText().trim();
    }
}
