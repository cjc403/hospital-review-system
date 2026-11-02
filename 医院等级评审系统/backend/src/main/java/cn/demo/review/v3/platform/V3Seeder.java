package cn.demo.review.v3.platform;

import cn.demo.review.v2.platform.ReviewProfile;
import cn.demo.review.v2.security.AuthContext;
import cn.demo.review.v3.ingestion.IngestionService;
import cn.demo.review.v3.metric.MetricDefinitionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.util.Set;

/**
 * v3 演示种子：指标定义、CSV 映射、一条已应用的 2026-09 合成批次。
 * 只在 demo profile 写入；合成数据停在 seed/adapter 层，核心规则不依赖它。
 */
@Component
@Order(20)
public class V3Seeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(V3Seeder.class);
    private static final String DEMO_PERIOD = "2026-09";

    private final ReviewProfile profile;
    private final JdbcTemplate jdbc;
    private final MetricDefinitionService definitions;
    private final IngestionService ingestion;
    private final ObjectMapper json;
    private final boolean richSeedEnabled;

    public V3Seeder(ReviewProfile profile, JdbcTemplate jdbc, MetricDefinitionService definitions,
                    IngestionService ingestion, ObjectMapper json,
                    @Value("${demo.rich-seed.enabled:true}") boolean richSeedEnabled) {
        this.profile = profile;
        this.jdbc = jdbc;
        this.definitions = definitions;
        this.ingestion = ingestion;
        this.json = json;
        this.richSeedEnabled = richSeedEnabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!profile.isDemo()) {
            log.info("prod profile：跳过 v3 演示种子");
            return;
        }
        String versionId = definitions.latestVersion();
        if (versionId == null) {
            log.warn("v3 种子跳过：尚无已发布标准版本");
            return;
        }
        int created = definitions.seedFromStandard(versionId);
        if (created > 0) log.info("seeded {} metric definitions", created);

        AuthContext system = new AuthContext("system-seed", "H001", null, Set.of("review_office"), "demo");
        Integer mappings = jdbc.queryForObject(
                "SELECT COUNT(*) FROM source_mapping WHERE hospital_id=? AND source_code='CSV-IMPORT'",
                Integer.class, "H001");
        if (mappings == null || mappings == 0) {
            ObjectNode mapping = json.createObjectNode();
            mapping.put("sourceCode", "CSV-IMPORT");
            ObjectNode fields = mapping.putObject("fields");
            fields.put("metricCode", "code");
            fields.put("deptCode", "dept");
            fields.put("numerator", "num");
            fields.put("denominator", "den");
            fields.put("value", "val");
            fields.put("unit", "unit");
            mapping.putObject("codeMap");
            ingestion.saveMapping(system, mapping, "seed");
            log.info("seeded CSV-IMPORT mapping");
        }

        // 丰富场景负责完整的 12 个月；精简基线仅供契约测试使用。
        if (richSeedEnabled) return;
        Integer batches = jdbc.queryForObject("SELECT COUNT(*) FROM ingestion_batch WHERE hospital_id=?",
                Integer.class, "H001");
        if (batches != null && batches > 0) return;

        ObjectNode body = json.createObjectNode();
        body.put("sourceCode", "CSV-IMPORT");
        body.put("period", DEMO_PERIOD);
        ArrayNode rows = body.putArray("rows");
        rows.add(row("i01", "CARD", 11, 2000, null, "%"));
        rows.add(row("i03", "SURG", 6, 2000, null, "%"));
        rows.add(row("i11", "ALL", 1180, 1000, null, ""));
        rows.add(row("i12", "ALL", null, null, 850, "张"));
        var detail = ingestion.createBatch(system, body, "seed");
        String batchId = String.valueOf(((java.util.Map<?, ?>) detail.get("batch")).get("id"));
        if ("validated".equals(String.valueOf(((java.util.Map<?, ?>) detail.get("batch")).get("status")))) {
            ingestion.apply(system, batchId, "seed");
            log.info("seeded demo ingestion batch {} for {}", batchId, DEMO_PERIOD);
        } else {
            log.warn("demo batch was rejected: {}", detail.get("issues"));
        }
    }

    private ObjectNode row(String code, String dept, Integer numerator, Integer denominator,
                           Integer value, String unit) {
        ObjectNode row = json.createObjectNode();
        row.put("code", code);
        row.put("dept", dept);
        if (numerator != null) row.put("num", numerator);
        if (denominator != null) row.put("den", denominator);
        if (value != null) row.put("val", value);
        row.put("unit", unit);
        return row;
    }
}
