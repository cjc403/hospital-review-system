package cn.demo.review.v3.metric;

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
 * 指标定义与版本：同一指标再次提交即生成新版本，没有修改/删除入口。
 * 演示定义从已发布标准包派生（i11 走 drg_formula、i12 走 year_end_snapshot），
 * 公式类型仍受 {@link FormulaEngine} 白名单约束。
 */
@Service
public class MetricDefinitionService {
    private static final Set<String> DIRECTIONS = Set.of("high", "low");

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public MetricDefinitionService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public Map<String, Object> list(AuthContext ctx, String versionId) {
        String version = versionId == null || versionId.isBlank() ? latestVersion() : versionId;
        if (version == null) throw V2Exception.conflict("尚无已发布的标准版本");
        List<Map<String, Object>> items = jdbc.query(
                "SELECT id, code, name, unit, direction, owner_scope, formula_type, aggregation, target_value,"
                        + " warning_value, version_no, status, effective_from FROM metric_definition"
                        + " WHERE standard_version_id=? AND version_no=(SELECT MAX(d2.version_no) FROM metric_definition d2"
                        + " WHERE d2.standard_version_id=metric_definition.standard_version_id AND d2.code=metric_definition.code)"
                        + " ORDER BY code",
                (rs, i) -> row(rs), version);
        return Map.of("versionId", version, "items", items);
    }

    public Map<String, Object> current(String hospitalId, String versionId, String code) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT id, code, name, unit, direction, owner_scope, formula_type, aggregation, target_value,"
                        + " warning_value, version_no, status, effective_from FROM metric_definition"
                        + " WHERE standard_version_id=? AND code=? ORDER BY version_no DESC",
                (rs, i) -> row(rs), versionId, code);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public FormulaEngine.Spec spec(Map<String, Object> definition) {
        return new FormulaEngine.Spec(
                (String) definition.get("code"),
                FormulaEngine.FormulaType.parse((String) definition.get("formulaType")),
                (String) definition.get("unit"),
                (String) definition.get("direction"),
                toDouble(definition.get("target")),
                toDouble(definition.get("warning")));
    }

    @Transactional
    public Map<String, Object> create(AuthContext ctx, JsonNode body, String requestId) {
        ctx.require("review_office");
        String versionId = text(body, "versionId");
        String code = text(body, "code");
        String name = text(body, "name");
        String direction = text(body, "direction");
        if (!DIRECTIONS.contains(direction)) throw V2Exception.badRequest("direction 只能是 high 或 low");
        List<String> versionStatuses = jdbc.query(
                "SELECT status FROM standard_version WHERE id=?",
                (rs, i) -> rs.getString(1), versionId);
        if (versionStatuses.isEmpty()) throw V2Exception.notFound("标准版本不存在");
        if (!"published".equals(versionStatuses.get(0))) {
            throw V2Exception.conflict("指标定义只能挂在已发布标准版本上");
        }
        String formulaType = text(body, "formulaType");
        if (!FormulaEngine.ALLOWED_TYPES.contains(formulaType)) {
            throw new V2Exception(400, "formula_not_allowed", "只允许受控公式类型：" + FormulaEngine.ALLOWED_TYPES);
        }
        Integer next = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_no),0)+1 FROM metric_definition WHERE standard_version_id=? AND code=?",
                Integer.class, versionId, code);
        String id = "def-" + UUID.randomUUID();
        jdbc.update("INSERT INTO metric_definition (id, standard_version_id, code, name, unit, direction, owner_scope,"
                        + " formula_type, aggregation, target_value, warning_value, version_no, status, effective_from,"
                        + " created_at, created_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, versionId, code, name, body.path("unit").asText(""), direction,
                body.path("ownerScope").asText(""), formulaType, body.path("aggregation").asText(""),
                body.path("target").isNumber() ? body.get("target").asDouble() : null,
                body.path("warning").isNumber() ? body.get("warning").asDouble() : null,
                next, "published", body.path("effectiveFrom").asText(""), now(), ctx.userId());
        audit.record(ctx, requestId, "新增指标定义版本", "metric_definition", id,
                "指标=" + code + " 版本=" + next + " 公式=" + formulaType);
        return list(ctx, versionId).get("items") instanceof List<?> items
                ? items.stream().filter(item -> code.equals(((Map<?, ?>) item).get("code")))
                    .findFirst().map(item -> (Map<String, Object>) item).orElseThrow()
                : Map.of();
    }

    /** 演示定义：从已发布标准包的指标派生；已存在则跳过。 */
    @Transactional
    public int seedFromStandard(String versionId) {
        Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM metric_definition WHERE standard_version_id=?", Integer.class, versionId);
        if (existing != null && existing > 0) return 0;
        List<Map<String, Object>> indicators = jdbc.query(
                "SELECT d.code, d.name, d.unit, d.direction, d.annual_agg, d.target_value, d.warn_value,"
                        + " c.owner_scope FROM indicator_definition d LEFT JOIN standard_clause c"
                        + " ON c.version_id=d.version_id AND c.code=d.clause_code WHERE d.version_id=? ORDER BY d.code",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("code", rs.getString("code"));
                    m.put("name", rs.getString("name"));
                    m.put("unit", rs.getString("unit"));
                    m.put("direction", rs.getString("direction"));
                    m.put("annualAgg", rs.getString("annual_agg"));
                    m.put("target", rs.getObject("target_value"));
                    m.put("warning", rs.getObject("warn_value"));
                    m.put("ownerScope", rs.getString("owner_scope"));
                    return m;
                }, versionId);
        String effectiveFrom = jdbc.query("SELECT effective_from FROM standard_version WHERE id=?",
                rs -> rs.next() ? rs.getString(1) : null, versionId);
        int created = 0;
        for (Map<String, Object> indicator : indicators) {
            String code = (String) indicator.get("code");
            String unit = (String) indicator.get("unit");
            String annualAgg = String.valueOf(indicator.get("annualAgg"));
            String formula = switch (annualAgg) {
                case "drg_formula" -> "drg_formula";
                case "year_end_snapshot" -> "year_end_snapshot";
                default -> "i13".equals(code) ? "weighted_ratio" : ("%".equals(unit) ? "ratio" : "month_avg");
            };
            jdbc.update("INSERT INTO metric_definition (id, standard_version_id, code, name, unit, direction,"
                            + " owner_scope, formula_type, aggregation, target_value, warning_value, version_no,"
                            + " status, effective_from, created_at, created_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    "def-" + UUID.randomUUID(), versionId, code, indicator.get("name"), unit,
                    indicator.get("direction"), indicator.get("ownerScope"), formula, annualAgg,
                    indicator.get("target"), indicator.get("warning"), 1, "published", effectiveFrom,
                    now(), "system-seed");
            created++;
        }
        return created;
    }

    public String latestVersion() {
        List<String> ids = jdbc.query("SELECT id FROM standard_version WHERE status='published' ORDER BY created_at DESC",
                (rs, i) -> rs.getString(1));
        return ids.isEmpty() ? null : ids.get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> row(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getString("id"));
        m.put("code", rs.getString("code"));
        m.put("name", rs.getString("name"));
        m.put("unit", rs.getString("unit"));
        m.put("direction", rs.getString("direction"));
        m.put("ownerScope", rs.getString("owner_scope"));
        m.put("formulaType", rs.getString("formula_type"));
        m.put("aggregation", rs.getString("aggregation"));
        m.put("target", rs.getObject("target_value"));
        m.put("warning", rs.getObject("warning_value"));
        m.put("versionNo", rs.getInt("version_no"));
        m.put("status", rs.getString("status"));
        m.put("effectiveFrom", rs.getString("effective_from"));
        return m;
    }

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
}
