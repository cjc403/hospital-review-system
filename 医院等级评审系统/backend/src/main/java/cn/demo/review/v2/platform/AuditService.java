package cn.demo.review.v2.platform;

import cn.demo.review.v2.security.AuthContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 审计：所有写操作与敏感读取都落一条不可变事件。
 * detail 只写摘要，不写文件路径、令牌或患者标识。
 */
@Service
public class AuditService {
    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void record(AuthContext ctx, HttpServletRequest request, String action,
                       String objectType, String objectId, String detail) {
        record(ctx, RequestIdFilter.of(request), action, objectType, objectId, detail);
    }

    public void record(AuthContext ctx, String requestId, String action,
                       String objectType, String objectId, String detail) {
        jdbc.update("INSERT INTO audit_event (id, hospital_id, actor, action_name, object_type, object_id, detail, request_id, created_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?)",
                "aud-" + UUID.randomUUID(), ctx.hospitalId(), ctx.userId(), action, objectType,
                objectId, trim(detail), requestId, now());
    }

    public List<Map<String, Object>> list(String hospitalId, int limit) {
        return jdbc.query("SELECT id, actor, action_name, object_type, object_id, detail, request_id, created_at"
                        + " FROM audit_event WHERE hospital_id=? ORDER BY created_at DESC LIMIT ?",
                (rs, i) -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("actor", rs.getString("actor"));
                    m.put("action", rs.getString("action_name"));
                    m.put("objectType", rs.getString("object_type"));
                    m.put("objectId", rs.getString("object_id"));
                    m.put("detail", rs.getString("detail"));
                    m.put("requestId", rs.getString("request_id"));
                    m.put("createdAt", rs.getString("created_at"));
                    return m;
                }, hospitalId, Math.min(limit, 200));
    }

    private static String trim(String detail) {
        if (detail == null) return "";
        return detail.length() <= 1000 ? detail : detail.substring(0, 1000);
    }

    private static String now() {
        return OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).toString();
    }
}
