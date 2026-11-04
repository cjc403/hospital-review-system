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
        apply(V2Schema.VERSION, V2Schema.DDL);
        apply(V3Schema.VERSION, V3Schema.DDL);
        apply(V4Schema.VERSION, V4Schema.DDL);
        apply(V5Schema.VERSION, V5Schema.DDL);
        apply(V6Schema.VERSION, V6Schema.DDL);
        apply(V7Schema.VERSION, V7Schema.DDL);
        apply(V8Schema.VERSION, V8Schema.DDL);
        apply(V9Schema.VERSION, V9Schema.DDL);
    }

    private void apply(int version, String[] ddl) {
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM schema_migration WHERE version=?", Integer.class, version);
        if (exists != null && exists > 0) return;
        for (String statement : ddl) jdbc.execute(statement);
        jdbc.update("INSERT INTO schema_migration (version, applied_at) VALUES (?, ?)",
                version, OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).toString());
        log.info("applied v0.2 schema migration version={}", version);
    }

    /** 供测试与运维确认当前已应用版本。 */
    public List<Integer> appliedVersions() {
        return jdbc.query("SELECT version FROM schema_migration ORDER BY version", (rs, row) -> rs.getInt(1));
    }
}
