package cn.demo.review;

import cn.demo.review.v2.platform.SchemaMigrator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 迁移方言代理测试：在 H2 的 PostgreSQL 兼容模式下执行同一套 DDL。
 * 这不是真实 PostgreSQL 验证——真实库执行需要用户提供隔离库与账号；
 * 它只保证 v0.2 DDL 不含 H2 专有语法，且迁移在"非默认方言"下仍可完成。
 */
class SchemaMigrationTest {
    @Test
    void appliesV2SchemaUnderPostgresCompatibilityMode() throws Exception {
        Path root = Files.createTempDirectory("hospital-pgmode-");
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(DemoApplication.class).run(
                "--server.port=0",
                "--spring.datasource.url=jdbc:h2:mem:pgmode;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "--review.storage-dir=" + root.resolve("evidence"),
                "--demo.upload-dir=" + root.resolve("uploads"),
                "--spring.main.banner-mode=off")) {
            SchemaMigrator migrator = context.getBean(SchemaMigrator.class);
            assertEquals(List.of(2, 3, 4, 5), migrator.appliedVersions(), "迁移应按版本递增且只应用一次");

            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            Integer columns = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='V3_IMPROVEMENT'"
                            + " AND COLUMN_NAME IN ('SOURCE_TYPE','TARGET_VALUE','BASELINE_VALUE','LATEST_VALUE')",
                    Integer.class);
            assertEquals(4, columns, "v0.5 追加列应存在");

            for (String table : List.of("HOSPITAL", "DEPARTMENT", "STANDARD_PACKAGE", "STANDARD_VERSION",
                    "STANDARD_CHAPTER", "STANDARD_CLAUSE", "INDICATOR_DEFINITION", "PREREQ_DEFINITION",
                    "REVIEW_TASK", "ASSIGNMENT_REQUEST", "EVIDENCE_ITEM", "EVIDENCE_VERSION", "DATA_SOURCE",
                    "COLLECTION_BATCH", "METRIC_FACT", "CALCULATION_TRACE", "QUALITY_ISSUE", "SCORE_RUN",
                    "SCORE_ITEM", "AUDIT_EVENT", "PREREQ_CONCLUSION", "AUDIT_SAMPLE",
                    "METRIC_DEFINITION", "SOURCE_MAPPING", "INGESTION_BATCH", "CANONICAL_FACT",
                    "V3_QUALITY_ISSUE", "V3_TRACE", "LINEAGE_EDGE", "METRIC_RESULT", "V3_ALERT",
                    "V3_IMPROVEMENT", "V3_REPORT")) {
                Integer count = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME=?", Integer.class, table);
                assertEquals(1, count, "缺少表 " + table);
            }
            Integer versions = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM standard_version WHERE status='published'", Integer.class);
            assertNotNull(versions);
        }
    }
}
