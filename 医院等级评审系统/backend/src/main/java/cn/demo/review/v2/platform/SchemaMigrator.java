package cn.demo.review.v2.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 追加式迁移器：每个版本只执行一次，执行记录写入 schema_migration。
 * 已发布的版本不再修改；新增结构一律追加新版本号。
 */
@Component
public class SchemaMigrator implements InitializingBean {
    private static final Logger log = LoggerFactory.getLogger(SchemaMigrator.class);
    private final JdbcTemplate jdbc;

    public SchemaMigrator(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void afterPropertiesSet() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS schema_migration (version INT PRIMARY KEY, applied_at VARCHAR(40) NOT NULL)");
        List<Integer> applied = jdbc.query("SELECT version FROM schema_migration",
                (rs, row) -> rs.getInt(1));
        if (applied.contains(V2Schema.VERSION)) return;
        for (String ddl : V2Schema.DDL) jdbc.execute(ddl);
        jdbc.update("INSERT INTO schema_migration (version, applied_at) VALUES (?, ?)",
                V2Schema.VERSION, OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).toString());
        log.info("applied v0.2 schema migration version={}", V2Schema.VERSION);
    }

    /** 供测试与运维确认当前已应用版本。 */
    public List<Integer> appliedVersions() {
        return jdbc.query("SELECT version FROM schema_migration ORDER BY version", (rs, row) -> rs.getInt(1));
    }
}
