package cn.demo.review.v2.platform;

import cn.demo.review.v2.security.AuthContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 组织范围读取：科室列表按医院过滤，科室负责人只能看到本科室。 */
@Service
public class OrganizationService {
    private final JdbcTemplate jdbc;

    public OrganizationService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Map<String, Object> departments(AuthContext ctx) {
        StringBuilder sql = new StringBuilder(
                "SELECT d.code, d.name, h.name AS hospital_name FROM department d"
                        + " JOIN hospital h ON h.id = d.hospital_id WHERE d.hospital_id=?");
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(ctx.hospitalId());
        if (ctx.deptCode() != null) {
            sql.append(" AND d.code=?");
            args.add(ctx.deptCode());
        }
        sql.append(" ORDER BY d.code");
        List<Map<String, Object>> items = jdbc.query(sql.toString(), (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", rs.getString("code"));
            m.put("name", rs.getString("name"));
            m.put("hospitalName", rs.getString("hospital_name"));
            return m;
        }, args.toArray());
        return Map.of("items", items);
    }
}
