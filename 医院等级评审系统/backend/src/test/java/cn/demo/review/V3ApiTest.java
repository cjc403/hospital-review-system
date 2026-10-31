package cn.demo.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v3 数据中台集成测试：指标定义版本、受控公式、批次质量门、结果血缘、预警状态机、PDCA 与报告快照。
 * 只用真实 HTTP 与合成数据；测试按顺序共享同一演示库，顺序即业务流程。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class V3ApiTest {
    ConfigurableApplicationContext context;
    ConfigurableApplicationContext prodContext;
    final HttpClient client = HttpClient.newHttpClient();
    final ObjectMapper json = new ObjectMapper();
    Path root;
    String base;
    String prodBase;
    String versionId;
    String alertId;
    String improvementId;
    String reportId;
    byte[] firstReport;

    @BeforeAll
    void start() throws Exception {
        root = Files.createTempDirectory("hospital-v3-");
        context = new SpringApplicationBuilder(DemoApplication.class).run(
                "--server.port=0",
                "--spring.datasource.url=jdbc:h2:file:" + root.resolve("v3state").toString().replace('\\', '/')
                        + ";DB_CLOSE_ON_EXIT=FALSE",
                "--review.storage-dir=" + root.resolve("v3-evidence"),
                "--demo.upload-dir=" + root.resolve("v1uploads"),
                "--spring.main.banner-mode=off");
        base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port") + "/api/v3";
        prodContext = new SpringApplicationBuilder(DemoApplication.class).run(
                "--server.port=0",
                "--review.profile=prod",
                "--spring.datasource.url=jdbc:h2:mem:v3prod;DB_CLOSE_DELAY=-1",
                "--review.storage-dir=" + root.resolve("prod-evidence"),
                "--spring.main.banner-mode=off");
        prodBase = "http://127.0.0.1:" + prodContext.getEnvironment().getProperty("local.server.port") + "/api/v3";
    }

    @AfterAll
    void stop() {
        if (context != null) context.close();
        if (prodContext != null) prodContext.close();
    }

    // ---------- 辅助 ----------

    HttpResponse<byte[]> call(String method, String path, String body, String role) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json");
        if (role != null) builder.header("X-Demo-Role", role);
        builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    JsonNode ok(String method, String path, String body, String role) throws Exception {
        HttpResponse<byte[]> response = call(method, path, body, role);
        String text = new String(response.body(), StandardCharsets.UTF_8);
        assertEquals(200, response.statusCode(), text);
        return json.readTree(text);
    }

    JsonNode ok(String method, String path, String body) throws Exception {
        return ok(method, path, body, "quality");
    }

    JsonNode error(int status, String method, String path, String body, String role) throws Exception {
        HttpResponse<byte[]> response = call(method, path, body, role);
        String text = new String(response.body(), StandardCharsets.UTF_8);
        assertEquals(status, response.statusCode(), text);
        return json.readTree(text);
    }

    String batchBody(String period, String rowsJson) {
        return "{\"sourceCode\":\"CSV-IMPORT\",\"period\":\"" + period + "\",\"rows\":[" + rowsJson + "]}";
    }

    static String row(String code, String dept, Double num, Double den, Double value, String unit) {
        StringBuilder out = new StringBuilder("{\"code\":\"").append(code).append("\"");
        if (dept != null) out.append(",\"dept\":\"").append(dept).append("\"");
        if (num != null) out.append(",\"num\":").append(num);
        if (den != null) out.append(",\"den\":").append(den);
        if (value != null) out.append(",\"val\":").append(value);
        if (unit != null) out.append(",\"unit\":\"").append(unit).append("\"");
        return out.append("}").toString();
    }

    JsonNode results(String period) throws Exception {
        return ok("GET", "/metric-results?period=" + period, null).path("items");
    }

    JsonNode findByCode(JsonNode items, String code, String dept) {
        for (JsonNode item : items) {
            if (code.equals(item.path("metricCode").asText()) && dept.equals(item.path("deptCode").asText())) {
                return item;
            }
        }
        throw new AssertionError("missing result " + code + "/" + dept + " in " + items);
    }

    // ---------- 1. 定义与公式 ----------

    @Test
    @Order(1)
    void metricDefinitionsAreVersionedAndFormulaRestricted() throws Exception {
        JsonNode list = ok("GET", "/metric-definitions", null);
        versionId = list.path("versionId").asText();
        JsonNode items = list.path("items");
        assertEquals(14, items.size(), "演示标准包的 14 个指标都应有定义");
        String i11Formula = null, i12Formula = null;
        for (JsonNode item : items) {
            if ("i11".equals(item.path("code").asText())) i11Formula = item.path("formulaType").asText();
            if ("i12".equals(item.path("code").asText())) i12Formula = item.path("formulaType").asText();
        }
        assertEquals("drg_formula", i11Formula);
        assertEquals("year_end_snapshot", i12Formula);

        JsonNode created = ok("POST", "/metric-definitions",
                "{\"versionId\":\"" + versionId + "\",\"code\":\"i01\",\"name\":\"手术患者院内死亡率（口径修订）\","
                        + "\"unit\":\"%\",\"direction\":\"low\",\"formulaType\":\"ratio\",\"target\":0.25,\"warning\":0.35}",
                "quality");
        assertEquals(2, created.path("versionNo").asInt(), "再次提交应生成新版本");

        JsonNode rejected = error(400, "POST", "/metric-definitions",
                "{\"versionId\":\"" + versionId + "\",\"code\":\"i01\",\"name\":\"非法公式\",\"direction\":\"low\","
                        + "\"formulaType\":\"sql_script\"}", "quality");
        assertEquals("formula_not_allowed", rejected.path("code").asText());
    }

    // ---------- 2. 质量门与重放 ----------

    @Test
    @Order(2)
    void badBatchIsBlockedWithLocatableIssues() throws Exception {
        String rows = row("i01", "CARD", 3000d, 2000d, null, "%") + ","
                + row("i99", "CARD", 1d, 2d, null, "%") + ","
                + row("i03", "NOPE", 1d, 2d, null, "%");
        JsonNode detail = ok("POST", "/ingestion/batches", batchBody("2026-10", rows));
        assertEquals("rejected", detail.path("batch").path("status").asText());
        Set<String> rules = new HashSet<>();
        for (JsonNode issue : detail.path("issues")) {
            rules.add(issue.path("rule").asText());
            assertTrue(issue.path("rowNo").asInt() > 0, "质量问题必须定位到行");
        }
        assertTrue(rules.contains("numerator_over_denominator"), rules.toString());
        assertTrue(rules.contains("unknown_metric"), rules.toString());
        assertTrue(rules.contains("unmapped_dept"), rules.toString());

        String batchId = detail.path("batch").path("id").asText();
        JsonNode blocked = error(409, "POST", "/ingestion/batches/" + batchId + "/apply", "{}", "quality");
        assertEquals("quality_blocked", blocked.path("code").asText());

        JsonNode replay = ok("POST", "/ingestion/batches/" + batchId + "/replay", "{}");
        assertEquals(batchId, replay.path("batch").path("replayOf").asText(), "重放必须生成新批次并指向原批次");
        assertNotEquals(batchId, replay.path("batch").path("id").asText());
    }

    @Test
    @Order(3)
    void validBatchAppliesAndComputesWeightedResults() throws Exception {
        String rows = row("i01", "CARD", 11d, 2000d, null, "%") + ","
                + row("i03", "SURG", 6d, 2000d, null, "%") + ","
                + row("i11", "CARD", 400d, 100d, null, "") + ","
                + row("i11", "SURG", 600d, 300d, null, "") + ","
                + row("i12", "ALL", null, null, 870d, "张");
        JsonNode detail = ok("POST", "/ingestion/batches", batchBody("2026-11", rows));
        assertEquals("validated", detail.path("batch").path("status").asText(), detail.toString());
        String batchId = detail.path("batch").path("id").asText();

        JsonNode applied = ok("POST", "/ingestion/batches/" + batchId + "/apply", "{}");
        assertEquals("applied", applied.path("batch").path("status").asText());
        JsonNode again = error(409, "POST", "/ingestion/batches/" + batchId + "/apply", "{}", "quality");
        assertEquals("immutable", again.path("code").asText());

        JsonNode items = results("2026-11");
        // i11：加权 Σ400+600 / Σ100+300 = 2.5；若退化按平均会是 (4+2)/2 = 3
        JsonNode i11All = findByCode(items, "i11", "ALL");
        assertEquals(2.5, i11All.path("value").asDouble(), 1e-9, "DRG 指标必须按加权聚合");
        assertEquals(1000d, i11All.path("numerator").asDouble(), 1e-9);
        assertEquals(400d, i11All.path("denominator").asDouble(), 1e-9);
        assertEquals("drg_formula", i11All.path("formulaType").asText());

        // i12：年末快照取原值，不平均
        assertEquals(870d, findByCode(items, "i12", "ALL").path("value").asDouble(), 1e-9);
        // i01：比例按分子分母
        assertEquals(0.55, findByCode(items, "i01", "CARD").path("value").asDouble(), 1e-9);
        assertEquals(0.55, findByCode(items, "i01", "ALL").path("value").asDouble(), 1e-9);

        String overlap = row("i01", "CARD", 10d, 2000d, null, "%");
        JsonNode overlapDetail = ok("POST", "/ingestion/batches", batchBody("2026-11", overlap));
        assertEquals("rejected", overlapDetail.path("batch").path("status").asText());
        boolean periodOverlap = false;
        for (JsonNode issue : overlapDetail.path("issues")) {
            if ("period_overlap".equals(issue.path("rule").asText())) periodOverlap = true;
        }
        assertTrue(periodOverlap, overlapDetail.toString());
    }

    // ---------- 3. 下钻、血缘与范围 ----------

    @Test
    @Order(4)
    void drilldownAndLineageExplainTheResult() throws Exception {
        JsonNode items = results("2026-11");
        JsonNode i11Card = findByCode(items, "i11", "CARD");
        JsonNode drilldown = ok("GET", "/metric-results/" + i11Card.path("id").asText() + "/drilldown", null);
        assertTrue(drilldown.path("departments").size() >= 2, "下钻应给出科室分布");
        assertTrue(drilldown.path("members").path("masked").asBoolean(), "成员明细必须是脱敏占位");

        JsonNode lineage = ok("GET", "/lineage/results/" + i11Card.path("id").asText(), null);
        Set<String> types = new HashSet<>();
        for (JsonNode node : lineage.path("nodes")) types.add(node.path("type").asText());
        assertTrue(types.contains("result") && types.contains("definition") && types.contains("batch")
                && types.contains("source"), types.toString());
    }

    @Test
    @Order(5)
    void departmentRoleOnlySeesOwnResultsAndUnknownIdIsNotFound() throws Exception {
        JsonNode scoped = ok("GET", "/metric-results?period=2026-11", null, "department").path("items");
        assertTrue(scoped.size() > 0);
        for (JsonNode item : scoped) {
            assertEquals("CARD", item.path("deptCode").asText(), "科室角色只能看到本科室结果");
        }
        error(404, "GET", "/metric-results/res-does-not-exist", null, "quality");
    }

    // ---------- 4. 预警与 PDCA ----------

    @Test
    @Order(6)
    void alertStateMachineIsEnforcedAndAudited() throws Exception {
        JsonNode list = ok("GET", "/alerts?status=open", null);
        JsonNode openAlert = null;
        for (JsonNode alert : list.path("items")) {
            if ("CARD".equals(alert.path("deptCode").asText())) {
                openAlert = alert;
                break;
            }
        }
        if (openAlert == null) {
            // 2026-09 种子批次产生过 CARD 预警；若已无 open，取任意一条
            openAlert = ok("GET", "/alerts", null).path("items").get(0);
        }
        alertId = openAlert.path("id").asText();

        JsonNode ack = ok("POST", "/alerts/" + alertId + "/ack", "{\"note\":\"已知悉\"}");
        assertEquals("acknowledged", ack.path("status").asText());

        JsonNode invalid = error(409, "POST", "/alerts/" + alertId + "/resolve", "{\"note\":\"跳过分派\"}", "quality");
        assertEquals("invalid_transition", invalid.path("code").asText());

        JsonNode assigned = ok("POST", "/alerts/" + alertId + "/assign",
                "{\"assignee\":\"心血管内科质控员\",\"dueDate\":\"2026-12-31\",\"note\":\"分派整改\"}");
        assertEquals("assigned", assigned.path("status").asText());

        JsonNode resolved = ok("POST", "/alerts/" + alertId + "/resolve", "{\"note\":\"已完成整改\"}");
        assertEquals("resolved", resolved.path("status").asText());
        JsonNode terminal = error(409, "POST", "/alerts/" + alertId + "/ignore", "{\"note\":\"再忽略\"}", "quality");
        assertEquals("immutable", terminal.path("code").asText());
    }

    @Test
    @Order(7)
    void improvementFollowsFromAlertAndNeedsReviewConclusion() throws Exception {
        JsonNode other = ok("GET", "/alerts", null).path("items");
        String sourceAlert = other.get(0).path("id").asText();
        JsonNode improvement = ok("POST", "/improvements/from-alert",
                "{\"alertId\":\"" + sourceAlert + "\",\"rootCause\":\"围手术期评估不足\","
                        + "\"action\":\"补充术前讨论与核查\",\"owner\":\"心血管内科\",\"dueDate\":\"2026-12-31\"}");
        improvementId = improvement.path("id").asText();
        assertEquals("open", improvement.path("status").asText());

        JsonNode bad = error(400, "POST", "/improvements/" + improvementId + "/review",
                "{\"conclusion\":\"whatever\",\"note\":\"乱填\"}", "quality");
        assertEquals("validation_failed", bad.path("code").asText());

        JsonNode closed = ok("POST", "/improvements/" + improvementId + "/review",
                "{\"conclusion\":\"partially_improved\",\"note\":\"已落实术前讨论，观察下期数据\"}");
        assertEquals("closed", closed.path("status").asText());
        assertEquals("partially_improved", closed.path("reviewConclusion").asText());

        JsonNode again = error(409, "POST", "/improvements/" + improvementId + "/review",
                "{\"conclusion\":\"improved\",\"note\":\"重复复核\"}", "quality");
        assertEquals("immutable", again.path("code").asText());
    }

    // ---------- 5. 报告快照 ----------

    @Test
    @Order(8)
    void reportSnapshotIsStableAndCarriesLineage() throws Exception {
        JsonNode report = ok("POST", "/reports",
                "{\"versionId\":\"" + versionId + "\",\"periodFrom\":\"2026-09\",\"periodTo\":\"2026-11\"}");
        reportId = report.path("id").asText();
        assertTrue(report.path("metricCount").asInt() > 0);
        assertTrue(report.path("batchIds").size() > 0, "报告必须记录引用的批次");

        HttpResponse<byte[]> document = client.send(HttpRequest.newBuilder(
                        URI.create(base + "/reports/" + reportId + "/document"))
                .header("X-Demo-Role", "quality").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, document.statusCode());
        firstReport = document.body();
        assertTrue(isValidDocx(firstReport));

        // 新批次进来后，旧报告字节不变
        String rows = row("i01", "CARD", 9d, 2000d, null, "%");
        JsonNode extra = ok("POST", "/ingestion/batches", batchBody("2026-12", rows));
        ok("POST", "/ingestion/batches/" + extra.path("batch").path("id").asText() + "/apply", "{}");

        HttpResponse<byte[]> again = client.send(HttpRequest.newBuilder(
                        URI.create(base + "/reports/" + reportId + "/document"))
                .header("X-Demo-Role", "quality").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertArrayEquals(firstReport, again.body(), "报告必须绑定快照，不随后续批次变化");
    }

    @Test
    @Order(9)
    void productionProfileRejectsDemoIdentityOnV3() throws Exception {
        HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(
                        URI.create(prodBase + "/metric-definitions"))
                .header("X-Demo-Role", "quality").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(403, response.statusCode());
        assertEquals("profile_forbidden", json.readTree(response.body()).path("code").asText());

        HttpResponse<byte[]> anonymous = client.send(HttpRequest.newBuilder(
                URI.create(prodBase + "/metric-definitions")).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(401, anonymous.statusCode());
    }

    @Test
    @Order(10)
    void worseningAndJumpAlertsAreRaisedAgainstPreviousPeriod() throws Exception {
        // i03 上一期为 0.3%，本期 1.0%：低优指标恶化且跳变超过 50%
        String rows = row("i03", "SURG", 20d, 2000d, null, "%");
        JsonNode detail = ok("POST", "/ingestion/batches", batchBody("2027-01", rows));
        assertEquals("validated", detail.path("batch").path("status").asText(), detail.toString());
        ok("POST", "/ingestion/batches/" + detail.path("batch").path("id").asText() + "/apply", "{}");

        JsonNode alerts = ok("GET", "/alerts", null).path("items");
        Set<String> types = new HashSet<>();
        for (JsonNode alert : alerts) {
            if ("i03".equals(alert.path("metricCode").asText())) types.add(alert.path("type").asText());
        }
        assertTrue(types.contains("worsening"), "连续恶化应产生预警：" + types);
        assertTrue(types.contains("jump"), "异常跳变应产生预警：" + types);
    }

    private static boolean isValidDocx(byte[] bytes) throws Exception {
        if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K') return false;
        boolean document = false;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if ("word/document.xml".equals(entry.getName())) document = true;
            }
        }
        return document;
    }
}
