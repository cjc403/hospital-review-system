package cn.demo.review;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DemoV2ScenarioSeederTest {
    @Test
    void seedsTasksEvidenceBatchesScoresAndReports() throws Exception {
        var root = Files.createTempDirectory("hospital-rich-v2-");
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(DemoApplication.class).run(
                "--server.port=0", "--review.auth.legacy-demo-headers=true",
                "--demo.rich-seed.enabled=true",
                "--spring.datasource.url=jdbc:h2:file:" + root.resolve("state").toString().replace('\\', '/')
                        + ";DB_CLOSE_ON_EXIT=FALSE",
                "--review.storage-dir=" + root.resolve("evidence"),
                "--demo.upload-dir=" + root.resolve("uploads"),
                "--spring.main.banner-mode=off")) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            assertTrue(count(jdbc, "SELECT COUNT(*) FROM review_task WHERE hospital_id='H001'") >= 30);
            assertTrue(count(jdbc, "SELECT COUNT(*) FROM evidence_item WHERE hospital_id='H001'") >= 12);
            assertTrue(count(jdbc, "SELECT COUNT(*) FROM collection_batch WHERE hospital_id='H001' AND status='applied'") >= 12);
            assertTrue(count(jdbc, "SELECT COUNT(*) FROM score_run WHERE hospital_id='H001'") >= 4);
            assertTrue(count(jdbc, "SELECT COUNT(*) FROM audit_sample WHERE hospital_id='H001'") >= 6);
            assertTrue(count(jdbc, "SELECT COUNT(*) FROM v3_report WHERE hospital_id='H001'") >= 3);
            assertTrue(count(jdbc, "SELECT COUNT(*) FROM v3_improvement WHERE hospital_id='H001' AND status='closed' AND evidence_item_id IS NOT NULL") >= 3);
            assertTrue(count(jdbc, "SELECT COUNT(*) FROM v3_improvement WHERE hospital_id='H001' AND status='closed' AND baseline_value > latest_value") >= 3);
            var json = context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class);
            for (String payload : jdbc.query("SELECT snapshot_json FROM v3_report WHERE hospital_id='H001'", (rs, i) -> rs.getString(1))) {
                var snapshot = json.readTree(payload);
                int expected = jdbc.queryForObject("SELECT COUNT(*) FROM metric_result WHERE hospital_id='H001' AND period_code>=? AND period_code<=?",
                        Integer.class, snapshot.path("periodFrom").asText(), snapshot.path("periodTo").asText());
                org.junit.jupiter.api.Assertions.assertEquals(expected, snapshot.path("results").size(), "报告不可静默截断结果");
                assertTrue(snapshot.path("evidence").size() >= 1, "报告需包含闭环复核证据");
            }
        }
    }

    private static int count(JdbcTemplate jdbc, String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }
}
