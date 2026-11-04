package cn.demo.review;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DemoScenarioSeederTest {
    @Test void upgradesLegacyDatabaseWithMultipleDefinitionVersionsAndCustomMetrics() throws Exception {
        var root = Files.createTempDirectory("hospital-rich-upgrade-");
        try (var context = new SpringApplicationBuilder(DemoApplication.class).run(
                "--server.port=0", "--review.auth.legacy-demo-headers=true", "--demo.rich-seed.enabled=false",
                "--spring.datasource.url=jdbc:h2:mem:upgrade-"+UUID.randomUUID(),
                "--review.storage-dir="+root.resolve("evidence"), "--demo.upload-dir="+root.resolve("uploads"))) {
            var jdbc=context.getBean(JdbcTemplate.class);
            for (String code : java.util.List.of("i02","custom_metric")) {
                jdbc.update("INSERT INTO metric_definition SELECT ?,standard_version_id,?,name,unit,direction,owner_scope,formula_type,aggregation,target_value,warning_value,?,status,effective_from,created_at,created_by FROM metric_definition WHERE code='i02' AND version_no=1",
                        "test-definition-"+code,code,code.equals("i02")?2:1);
            }
            new cn.demo.review.demo.DemoScenarioSeeder(context.getBean(cn.demo.review.v2.platform.ReviewProfile.class),jdbc,
                    context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class),context.getBean(cn.demo.review.v3.ingestion.IngestionService.class),true).run(null);
            assertEquals(6,count(jdbc,"SELECT COUNT(*) FROM ingestion_batch WHERE created_by='demo-scenario-seed' AND status='rejected'"));
            assertEquals(0,count(jdbc,"SELECT COUNT(*) FROM canonical_fact WHERE metric_code='custom_metric'"));
            assertEquals(14,count(jdbc,"SELECT COUNT(DISTINCT metric_code) FROM metric_result WHERE period_code='2026-09'"));
            assertEquals(0,count(jdbc,"SELECT COUNT(*) FROM metric_result r JOIN ingestion_batch b ON b.id=r.batch_id WHERE b.status='rejected'"));
        }
    }
    @Test
    void productionProfileNeverCreatesRichFixturesEvenWhenEnabled() throws Exception {
        var root = Files.createTempDirectory("hospital-prod-no-rich-");
        try (var context = new SpringApplicationBuilder(DemoApplication.class).run(
                "--server.port=0", "--review.auth.legacy-demo-headers=true", "--review.profile=prod", "--demo.rich-seed.enabled=true",
                "--spring.datasource.url=jdbc:h2:mem:prod-no-rich-" + UUID.randomUUID(),
                "--review.storage-dir=" + root.resolve("evidence"),
                "--demo.upload-dir=" + root.resolve("uploads"), "--spring.main.banner-mode=off")) {
            var state = context.getBean(cn.demo.review.persistence.SnapshotRepository.class).load().orElseThrow();
            assertTrue(state.workspace.indicators.isEmpty());
            try (var files = Files.list(root.resolve("uploads"))) { assertEquals(0, files.count()); }
            assertEquals(0, count(context.getBean(JdbcTemplate.class), "SELECT COUNT(*) FROM demo_seed_marker"));
        }
    }
    @Test
    void seedsRichV3ScenarioOnceAndKeepsCountsOnRestart() throws Exception {
        var root = Files.createTempDirectory("hospital-rich-demo-");
        String db = "jdbc:h2:file:" + root.resolve("state").toString().replace('\\', '/')
                + ";DB_CLOSE_ON_EXIT=FALSE";
        int before;
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(DemoApplication.class).run(
                "--server.port=0", "--review.auth.legacy-demo-headers=true", "--spring.datasource.url=" + db,
                "--demo.rich-seed.enabled=true",
                "--review.storage-dir=" + root.resolve("evidence"),
                "--demo.upload-dir=" + root.resolve("uploads"),
                "--spring.main.banner-mode=off")) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            assertTrue(count(jdbc, "SELECT COUNT(*) FROM ingestion_batch WHERE hospital_id='H001' AND status='applied'") >= 12);
            assertTrue(count(jdbc, "SELECT COUNT(*) FROM ingestion_batch WHERE hospital_id='H001' AND status='rejected'") >= 4);
            assertTrue(count(jdbc, "SELECT COUNT(*) FROM metric_result WHERE hospital_id='H001'") >= 500);
            assertTrue(count(jdbc, "SELECT COUNT(DISTINCT alert_type) FROM v3_alert WHERE hospital_id='H001'") >= 5);
            assertEquals(14, count(jdbc, "SELECT COUNT(DISTINCT metric_code) FROM metric_result WHERE hospital_id='H001' AND period_code='2026-09'"));
            assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM demo_seed_marker WHERE scenario_code='group-demo-2026'"));
            before = count(jdbc, "SELECT COUNT(*) FROM ingestion_batch WHERE hospital_id='H001'");
        }
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(DemoApplication.class).run(
                "--server.port=0", "--review.auth.legacy-demo-headers=true", "--spring.datasource.url=" + db,
                "--demo.rich-seed.enabled=true",
                "--review.storage-dir=" + root.resolve("evidence"),
                "--demo.upload-dir=" + root.resolve("uploads"),
                "--spring.main.banner-mode=off")) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            assertEquals(before, count(jdbc, "SELECT COUNT(*) FROM ingestion_batch WHERE hospital_id='H001'"));
            assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM demo_seed_marker WHERE scenario_code='group-demo-2026'"));
        }
    }

    private static int count(JdbcTemplate jdbc, String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }
}

