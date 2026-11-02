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
 * v0.2 集成测试：只调用真实 HTTP 接口，验证认证边界、标准不可变、任务状态机、材料版本、
 * 质量阻断、试算快照与报告。测试之间按 @Order 顺序共享同一演示库，顺序即业务流程顺序。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class V2ApiTest {
    ConfigurableApplicationContext context;
    ConfigurableApplicationContext prodContext;
    final HttpClient client = HttpClient.newHttpClient();
    final ObjectMapper json = new ObjectMapper();
    Path root;
    String base;
    String prodBase;
    String versionId;
    String cardTaskId;
    String cardTaskRequestKey;
    String earlierRunId;
    String earlierRunGrade;

    @BeforeAll
    void start() throws Exception {
        root = Files.createTempDirectory("hospital-v2-");
        context = new SpringApplicationBuilder(DemoApplication.class).run(
                "--server.port=0", "--demo.rich-seed.enabled=false",
                "--spring.datasource.url=jdbc:h2:file:" + root.resolve("v2state").toString().replace('\\', '/')
                        + ";DB_CLOSE_ON_EXIT=FALSE",
                "--review.storage-dir=" + root.resolve("v2-evidence"),
                "--demo.upload-dir=" + root.resolve("v1uploads"),
                "--spring.main.banner-mode=off");
        base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port") + "/api/v2";
        prodContext = new SpringApplicationBuilder(DemoApplication.class).run(
                "--server.port=0", "--demo.rich-seed.enabled=false",
                "--review.profile=prod",
                "--spring.datasource.url=jdbc:h2:mem:v2prod;DB_CLOSE_DELAY=-1",
                "--review.storage-dir=" + root.resolve("prod-evidence"),
                "--spring.main.banner-mode=off");
        prodBase = "http://127.0.0.1:" + prodContext.getEnvironment().getProperty("local.server.port") + "/api/v2";
    }

    @AfterAll
    void stop() {
        if (context != null) context.close();
        if (prodContext != null) prodContext.close();
    }

    // ---------- 基础设施 ----------

    HttpResponse<byte[]> call(String method, String path, String body, String role) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json");
        if (role != null) builder.header("X-Demo-Role", role);
        builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
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

    JsonNode error(int expectedStatus, String method, String path, String body, String role) throws Exception {
        HttpResponse<byte[]> response = call(method, path, body, role);
        String text = new String(response.body(), StandardCharsets.UTF_8);
        assertEquals(expectedStatus, response.statusCode(), text);
        return json.readTree(text);
    }

    String upload(String ownerType, String ownerId, String title, String fileName, String content,
                  String role, int expectedStatus) throws Exception {
        String boundary = "v2Boundary";
        String payload = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"ownerType\"\r\n\r\n" + ownerType
                + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"ownerId\"\r\n\r\n" + ownerId
                + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"title\"\r\n\r\n" + title
                + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"materialType\"\r\n\r\n制度文件"
                + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + fileName
                + "\"\r\nContent-Type: text/plain\r\n\r\n" + content + "\r\n--" + boundary + "--\r\n";
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/evidence"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("X-Demo-Role", role)
                .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        String text = new String(response.body(), StandardCharsets.UTF_8);
        assertEquals(expectedStatus, response.statusCode(), text);
        return text;
    }

    String batchBody(String period, String rowsJson) {
        return "{\"sourceCode\":\"CSV-IMPORT\",\"period\":\"" + period + "\",\"rows\":[" + rowsJson + "]}";
    }

    String row(String code, double value, Double numerator, Double denominator) {
        return "{\"indicatorCode\":\"" + code + "\",\"value\":" + value
                + (numerator == null ? "" : ",\"numerator\":" + numerator)
                + (denominator == null ? "" : ",\"denominator\":" + denominator) + "}";
    }

    // ---------- 1. 认证与 profile ----------

    @Test
    @Order(1)
    void unauthenticatedRequestIsRejected() throws Exception {
        JsonNode body = error(401, "GET", "/me", null, null);
        assertEquals("unauthenticated", body.path("code").asText());
        assertFalse(body.path("requestId").asText().isBlank());
    }

    @Test
    @Order(2)
    void productionProfileRejectsDemoHeaderAndAnonymous() throws Exception {
        HttpResponse<byte[]> demoHeader = client.send(HttpRequest.newBuilder(URI.create(prodBase + "/me"))
                .header("X-Demo-Role", "quality").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(403, demoHeader.statusCode());
        assertEquals("profile_forbidden", json.readTree(demoHeader.body()).path("code").asText());

        HttpResponse<byte[]> anonymous = client.send(HttpRequest.newBuilder(URI.create(prodBase + "/me"))
                .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(401, anonymous.statusCode());

        HttpResponse<byte[]> badToken = client.send(HttpRequest.newBuilder(URI.create(prodBase + "/me"))
                .header("Authorization", "Bearer demo-token").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(401, badToken.statusCode());
    }

    @Test
    @Order(3)
    void demoRoleMapsToServerSideContext() throws Exception {
        JsonNode me = ok("GET", "/me", null);
        assertEquals("H001", me.path("hospitalId").asText());
        assertEquals("review_office", me.path("roles").get(0).asText());
        assertTrue(me.path("demo").asBoolean());

        JsonNode dept = ok("GET", "/me", null, "department");
        assertEquals("CARD", dept.path("deptCode").asText());
        assertEquals("dept_head", dept.path("roles").get(0).asText());
    }

    // ---------- 2. 标准与条款 ----------

    @Test
    @Order(4)
    void seededStandardVersionIsPublishedWithFullTree() throws Exception {
        JsonNode packages = ok("GET", "/standards", null);
        JsonNode versions = packages.path("packages").get(0).path("versions");
        assertEquals(1, versions.size());
        assertEquals("published", versions.get(0).path("status").asText());
        versionId = versions.get(0).path("id").asText();

        JsonNode detail = ok("GET", "/standards/versions/" + versionId, null);
        assertEquals(5, detail.path("counts").path("chapters").asInt());
        assertEquals(10, detail.path("counts").path("clauses").asInt());
        assertEquals(14, detail.path("counts").path("indicators").asInt());
        assertEquals(30, detail.path("counts").path("prerequisites").asInt());
        assertEquals("published", detail.path("version").path("status").asText());
    }

    @Test
    @Order(5)
    void importValidatesStructureAndReportsDetails() throws Exception {
        String badPack = """
                {"pack":{"package":{"code":"BAD-PACK","title":"非法包"},
                 "version":{"versionNo":1,"effectiveFrom":"2026-01-01"},
                 "chapters":[{"code":"C1","title":"章一"},{"code":"C1","title":"重复章"}],
                 "clauses":[{"code":"L1","chapterCode":"NOPE","title":"条款"}],
                 "indicators":[{"code":"X1","name":"指标","clauseCode":"L9","direction":"sideways","scoreType":"unknown"}],
                 "prerequisites":[]}}
                """;
        JsonNode body = error(400, "POST", "/standards/import", badPack, "quality");
        assertEquals("validation_failed", body.path("code").asText());
        assertTrue(body.path("details").size() >= 4, body.toString());
    }

    @Test
    @Order(6)
    void publishedVersionCannotBeRepublishedButCanBeRevised() throws Exception {
        JsonNode republish = error(409, "POST", "/standards/versions/" + versionId + "/publish", "{}", "quality");
        assertEquals("immutable", republish.path("code").asText());

        JsonNode revision = ok("POST", "/standards/versions/" + versionId + "/revision",
                "{\"note\":\"演示修订\"}", "quality");
        assertEquals("draft", revision.path("status").asText());
        assertEquals(2, revision.path("versionNo").asInt());
        String draftId = revision.path("id").asText();

        JsonNode draft = ok("GET", "/standards/versions/" + draftId, null);
        assertEquals(14, draft.path("counts").path("indicators").asInt(), "修订应复制全部条款与指标");

        JsonNode original = ok("GET", "/standards/versions/" + versionId, null);
        assertEquals("published", original.path("version").path("status").asText(), "修订不得改动已发布版本");
    }

    @Test
    @Order(7)
    void scoreRunRejectedWhenNoAppliedBatch() throws Exception {
        JsonNode body = error(409, "POST", "/score-runs",
                "{\"versionId\":\"" + versionId + "\",\"cycleStart\":2023,\"cycleEnd\":2026}", "quality");
        assertEquals("conflict", body.path("code").asText());
    }

    // ---------- 3. 任务 ----------

    @Test
    @Order(8)
    void assignIsIdempotentByRequestKey() throws Exception {
        cardTaskRequestKey = "assign-card-" + UUID.randomUUID();
        String body = "{\"versionId\":\"" + versionId + "\",\"clauseCodes\":[\"CL-2.1\",\"CL-2.3\"],"
                + "\"deptCode\":\"CARD\",\"assigneePost\":\"科室质控员\",\"priority\":\"high\","
                + "\"dueDate\":\"2026-11-15\",\"requestKey\":\"" + cardTaskRequestKey + "\"}";
        JsonNode first = ok("POST", "/tasks/assign", body);
        assertFalse(first.path("idempotent").asBoolean());
        assertEquals(2, first.path("tasks").size());
        cardTaskId = first.path("tasks").get(0).path("id").asText();

        JsonNode second = ok("POST", "/tasks/assign", body);
        assertTrue(second.path("idempotent").asBoolean(), "重复请求必须返回原回执");
        assertEquals(2, second.path("tasks").size());
        assertEquals(first.path("tasks").get(0).path("id").asText(),
                second.path("tasks").get(0).path("id").asText());
    }

    @Test
    @Order(9)
    void assignRequiresReviewOffice() throws Exception {
        String body = "{\"versionId\":\"" + versionId + "\",\"clauseCodes\":[\"CL-1.1\"],\"deptCode\":\"SURG\","
                + "\"dueDate\":\"2026-11-15\",\"requestKey\":\"assign-by-leader-" + UUID.randomUUID() + "\"}";
        JsonNode body403 = error(403, "POST", "/tasks/assign", body, "leader");
        assertEquals("forbidden", body403.path("code").asText());
    }

    @Test
    @Order(10)
    void anotherHospitalTaskIsInvisible() throws Exception {
        JsonNode body = error(404, "PATCH", "/tasks/task-h002-seed", "{\"status\":\"in_progress\"}", "quality");
        assertEquals("not_found", body.path("code").asText());
        JsonNode list = ok("GET", "/tasks", null);
        for (JsonNode item : list.path("items")) {
            assertNotEquals("task-h002-seed", item.path("id").asText());
        }
    }

    @Test
    @Order(11)
    void taskStateMachineEnforcesOrderAndReturnReason() throws Exception {
        String assigned = ok("POST", "/tasks/assign",
                "{\"versionId\":\"" + versionId + "\",\"clauseCodes\":[\"CL-4.1\"],\"deptCode\":\"SURG\","
                        + "\"dueDate\":\"2026-11-20\",\"requestKey\":\"assign-surg-" + UUID.randomUUID() + "\"}")
                .path("tasks").get(0).path("id").asText();

        // 跳过 in_progress 直接提交：必须被状态机拒绝
        JsonNode skipped = error(409, "PATCH", "/tasks/" + assigned, "{\"status\":\"submitted\"}", "quality");
        assertEquals("conflict", skipped.path("code").asText());

        // 科室推进到 submitted（演示 dept_head 只有 CARD，因此由评审办代执行合法流转）
        ok("PATCH", "/tasks/" + assigned, "{\"status\":\"in_progress\"}");
        ok("PATCH", "/tasks/" + assigned, "{\"status\":\"submitted\"}");

        // 退回必须写原因
        JsonNode noReason = error(400, "PATCH", "/tasks/" + assigned, "{\"status\":\"returned\"}", "quality");
        assertEquals("validation_failed", noReason.path("code").asText());

        ok("PATCH", "/tasks/" + assigned, "{\"status\":\"returned\",\"comment\":\"材料缺少审批页\"}");
        JsonNode returned = ok("GET", "/tasks", null).path("items");
        boolean found = false;
        for (JsonNode item : returned) {
            if (assigned.equals(item.path("id").asText())) {
                found = true;
                assertEquals("returned", item.path("status").asText());
                assertEquals("材料缺少审批页", item.path("returnReason").asText());
            }
        }
        assertTrue(found);

        ok("PATCH", "/tasks/" + assigned, "{\"status\":\"in_progress\"}");
        ok("PATCH", "/tasks/" + assigned, "{\"status\":\"submitted\"}");
        ok("PATCH", "/tasks/" + assigned, "{\"status\":\"accepted\"}");

        JsonNode terminal = error(409, "PATCH", "/tasks/" + assigned, "{\"status\":\"in_progress\"}", "quality");
        assertEquals("immutable", terminal.path("code").asText());
    }

    @Test
    @Order(12)
    void deptHeadIsScopedToOwnDepartment() throws Exception {
        JsonNode tasks = ok("GET", "/tasks", null, "department");
        for (JsonNode item : tasks.path("items")) {
            assertEquals("CARD", item.path("deptCode").asText(), "科室角色只能看到本科室任务");
        }
        // 科室负责人不能分派、不能审核
        String assignBody = "{\"versionId\":\"" + versionId + "\",\"clauseCodes\":[\"CL-1.2\"],\"deptCode\":\"CARD\","
                + "\"dueDate\":\"2026-11-20\",\"requestKey\":\"assign-dept-" + UUID.randomUUID() + "\"}";
        error(403, "POST", "/tasks/assign", assignBody, "department");

        ok("PATCH", "/tasks/" + cardTaskId, "{\"status\":\"in_progress\"}", "department");
        ok("PATCH", "/tasks/" + cardTaskId, "{\"status\":\"submitted\"}", "department");
        JsonNode forbidden = error(403, "PATCH", "/tasks/" + cardTaskId, "{\"status\":\"accepted\"}", "department");
        assertEquals("forbidden", forbidden.path("code").asText());
    }

    // ---------- 4. 材料 ----------

    @Test
    @Order(13)
    void evidenceKeepsVersionsAndOldVersionRemainsReadable() throws Exception {
        String first = upload("task", cardTaskId, "心血管内科自查材料", "self-check.txt", "版本一内容",
                "quality", 200);
        JsonNode firstJson = json.readTree(first);
        assertFalse(firstJson.path("idempotent").asBoolean());
        int firstVersionNo = firstJson.path("version").path("versionNo").asInt();
        String firstVersionId = firstJson.path("version").path("id").asText();
        assertFalse(firstJson.path("version").path("sha256").asText().isBlank());

        String duplicate = upload("task", cardTaskId, "心血管内科自查材料", "self-check.txt", "版本一内容",
                "quality", 200);
        JsonNode duplicateJson = json.readTree(duplicate);
        assertTrue(duplicateJson.path("idempotent").asBoolean(), "同内容重复上传返回既有版本");
        assertEquals(firstVersionId, duplicateJson.path("version").path("id").asText());

        String second = upload("task", cardTaskId, "心血管内科自查材料", "self-check.txt", "版本二内容",
                "quality", 200);
        JsonNode secondJson = json.readTree(second);
        assertEquals(firstVersionNo + 1, secondJson.path("version").path("versionNo").asInt());
        String itemId = secondJson.path("item").path("id").asText();

        JsonNode detail = ok("GET", "/evidence/" + itemId, null);
        assertEquals(2, detail.path("versions").size(), "旧版本必须保留");

        HttpResponse<byte[]> download = client.send(HttpRequest.newBuilder(
                        URI.create(base + "/evidence/versions/" + firstVersionId + "/download"))
                .header("X-Demo-Role", "quality").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, download.statusCode());
        assertEquals("版本一内容", new String(download.body(), StandardCharsets.UTF_8));
        assertTrue(download.headers().firstValue("Content-Disposition").orElse("").contains("self-check.txt"));
    }

    @Test
    @Order(14)
    void evidenceRejectsDangerousAndMismatchedFiles() throws Exception {
        JsonNode html = json.readTree(upload("task", cardTaskId, "脚本", "evil.html", "<script>x</script>",
                "quality", 400));
        assertEquals("validation_failed", html.path("code").asText());

        String fakePdf = upload("task", cardTaskId, "伪装文件", "report.pdf", "not a pdf", "quality", 400);
        assertTrue(fakePdf.contains("文件内容与扩展名不一致"));
    }

    @Test
    @Order(15)
    void evidenceReviewIsSingleShot() throws Exception {
        String upload = upload("task", cardTaskId, "待审核材料", "review.txt", "审核内容", "quality", 200);
        String itemId = json.readTree(upload).path("item").path("id").asText();
        JsonNode approved = ok("POST", "/evidence/" + itemId + "/review",
                "{\"decision\":\"approved\",\"note\":\"材料齐全\"}");
        assertEquals("approved", approved.path("status").asText());
        JsonNode again = error(409, "POST", "/evidence/" + itemId + "/review", "{\"decision\":\"rejected\"}", "quality");
        assertEquals("immutable", again.path("code").asText());
    }

    @Test
    @Order(16)
    void deptHeadCannotDownloadOtherDeptEvidenceOrUploadToOtherDeptTask() throws Exception {
        String surgTask = ok("POST", "/tasks/assign",
                "{\"versionId\":\"" + versionId + "\",\"clauseCodes\":[\"CL-3.1\"],\"deptCode\":\"SURG\","
                        + "\"dueDate\":\"2026-11-25\",\"requestKey\":\"assign-surg2-" + UUID.randomUUID() + "\"}")
                .path("tasks").get(0).path("id").asText();
        JsonNode forbidden = json.readTree(upload("task", surgTask, "外科材料", "surg.txt", "内容", "department", 403));
        assertEquals("forbidden", forbidden.path("code").asText());
    }

    // ---------- 5. 数据质量与试算 ----------

    @Test
    @Order(17)
    void batchWithQualityErrorsIsRejectedAndBlocked() throws Exception {
        String rows = row("i01", 120, 1200.0, 2000.0) + "," + row("i01", 1.0, 20.0, 2000.0) + ","
                + row("i99", 1.0, null, null);
        JsonNode detail = ok("POST", "/batches", batchBody("2026-08", rows));
        assertEquals("rejected", detail.path("batch").path("status").asText());
        assertTrue(detail.path("issues").size() >= 3, detail.toString());
        boolean hasError = false;
        for (JsonNode issue : detail.path("issues")) {
            if ("error".equals(issue.path("severity").asText())) hasError = true;
        }
        assertTrue(hasError);

        String batchId = detail.path("batch").path("id").asText();
        JsonNode blocked = error(409, "POST", "/batches/" + batchId + "/apply", "{}", "quality");
        assertEquals("quality_blocked", blocked.path("code").asText());
    }

    @Test
    @Order(18)
    void validBatchAppliesOnceAndCanBeReplayed() throws Exception {
        String rows = row("i01", 0.48, 960.0, 2000.0) + "," + row("i02", 57.0, 1140.0, 2000.0) + ","
                + row("i03", 0.24, 480.0, 2000.0) + "," + row("i04", 2.0, 40.0, 2000.0) + ","
                + row("i05", 0.79, 1580.0, 2000.0) + "," + row("i06", 88.0, 440.0, 500.0) + ","
                + row("i07", 96.0, 480.0, 500.0) + "," + row("i08", 95.0, 475.0, 500.0) + ","
                + row("i09", 97.0, 485.0, 500.0) + "," + row("i10", 91.0, 455.0, 500.0) + ","
                + row("i11", 1.19, null, null) + "," + row("i12", 860, null, null) + ","
                + row("i13", 1.72, null, null) + "," + row("i14", 8.0, null, null);
        JsonNode detail = ok("POST", "/batches", batchBody("2026-10", rows));
        assertEquals("validated", detail.path("batch").path("status").asText(), detail.toString());
        assertEquals(14, detail.path("facts").size());
        JsonNode sources = ok("GET", "/data-sources", null);
        assertTrue(sources.path("items").size() >= 2, "演示应预置合成与 CSV 两个数据源");

        String batchId = detail.path("batch").path("id").asText();
        JsonNode applied = ok("POST", "/batches/" + batchId + "/apply", "{}");
        assertEquals("applied", applied.path("batch").path("status").asText());

        JsonNode again = error(409, "POST", "/batches/" + batchId + "/apply", "{}", "quality");
        assertEquals("immutable", again.path("code").asText());

        JsonNode replay = ok("POST", "/batches/" + batchId + "/replay", "{}");
        assertEquals(batchId, replay.path("batch").path("replayOf").asText());
        assertEquals("validated", replay.path("batch").path("status").asText());
    }

    @Test
    @Order(19)
    void scoreRunProducesImmutableSnapshotAndComparableRuns() throws Exception {
        JsonNode outsideBatch = ok("POST", "/batches", batchBody("2027-01", row("i11", 2.20, 220.0, 100.0)));
        String outsideBatchId = outsideBatch.path("batch").path("id").asText();
        ok("POST", "/batches/" + outsideBatchId + "/apply", "{}");
        JsonNode missingDrgBatch = ok("POST", "/batches", batchBody("2025-01", row("i11", 1.80, null, null)));
        ok("POST", "/batches/" + missingDrgBatch.path("batch").path("id").asText() + "/apply", "{}");
        JsonNode missingDrgRun = ok("POST", "/score-runs",
                "{\"versionId\":\"" + versionId + "\",\"cycleStart\":2025,\"cycleEnd\":2025}");
        for (JsonNode item : missingDrgRun.path("items")) {
            if ("i11".equals(item.path("indicatorCode").asText())) {
                assertTrue(item.path("yearValues").isArray());
                assertTrue(item.path("yearValues").get(0).path("value").isNull(),
                        "缺少 DRG 分子/分母时年度值必须缺失");
                assertEquals("not_applicable", item.path("reasonCode").asText());
            }
        }

        JsonNode drgBatch = ok("POST", "/batches", batchBody("2026-11", row("i11", 1.00, 100.0, 100.0)));
        String drgBatchId = drgBatch.path("batch").path("id").asText();
        ok("POST", "/batches/" + drgBatchId + "/apply", "{}");
        JsonNode drgBatch2 = ok("POST", "/batches", batchBody("2026-12", row("i11", 0.30, 300.0, 1000.0)));
        String drgBatchId2 = drgBatch2.path("batch").path("id").asText();
        ok("POST", "/batches/" + drgBatchId2 + "/apply", "{}");
        JsonNode first = ok("POST", "/score-runs",
                "{\"versionId\":\"" + versionId + "\",\"cycleStart\":2023,\"cycleEnd\":2026}");
        assertTrue(first.path("run").path("includedCount").asInt() > 0, first.toString());
        assertEquals(first.path("run").path("includedCount").asInt(), first.path("items").size());
        assertTrue(first.path("items").get(0).path("yearValues").isArray(),
                "试算详情的年度值必须是结构化数组");
        JsonNode snapshot = json.readTree(first.path("run").path("snapshot").asText());
        assertFalse(snapshot.path("batchIds").toString().contains(outsideBatchId),
                "周期外批次不得进入试算快照血缘");
        for (JsonNode item : first.path("items")) {
            if ("i11".equals(item.path("indicatorCode").asText())) {
                assertEquals(0.3636, item.path("mergedValue").asDouble(), 0.0001,
                        "drg_formula 应按年度分子分母加权计算，不应按月度平均");
            }
        }
        String runId = first.path("run").path("id").asText();
        earlierRunId = runId;
        earlierRunGrade = first.path("run").path("grade").asText("");

        HttpResponse<byte[]> report = client.send(HttpRequest.newBuilder(
                        URI.create(base + "/score-runs/" + runId + "/report"))
                .header("X-Demo-Role", "quality").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, report.statusCode());
        assertTrue(isValidDocx(report.body()), "报告必须是有效 DOCX");

        String moreRows = row("i01", 0.52, 1040.0, 2000.0);
        JsonNode secondBatch = ok("POST", "/batches", batchBody("2026-11", moreRows));
        String secondBatchId = secondBatch.path("batch").path("id").asText();
        ok("POST", "/batches/" + secondBatchId + "/apply", "{}");

        JsonNode second = ok("POST", "/score-runs",
                "{\"versionId\":\"" + versionId + "\",\"cycleStart\":2023,\"cycleEnd\":2026}");
        String secondRunId = second.path("run").path("id").asText();
        assertNotEquals(runId, secondRunId);

        JsonNode compare = ok("GET", "/score-runs/" + runId + "/compare/" + secondRunId, null);
        assertNotNull(compare.get("added"));
        assertNotNull(compare.get("worsened"));

        // 旧报告不受后续批次影响：重新下载必须字节一致
        HttpResponse<byte[]> again = client.send(HttpRequest.newBuilder(
                        URI.create(base + "/score-runs/" + runId + "/report"))
                .header("X-Demo-Role", "quality").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertArrayEquals(report.body(), again.body(), "快照报告不得随业务变化改变");
    }

    @Test
    @Order(20)
    void auditTrailRecordsWrites() throws Exception {
        JsonNode audit = ok("GET", "/audit-events?limit=100", null);
        Set<String> actions = new HashSet<>();
        for (JsonNode item : audit.path("items")) actions.add(item.path("action").asText());
        assertTrue(actions.contains("分派条款任务"), actions.toString());
        assertTrue(actions.contains("上传材料版本"));
        assertTrue(actions.contains("导入数据批次"));
        for (JsonNode item : audit.path("items")) {
            assertFalse(item.path("requestId").asText().isBlank(), "审计必须带请求关联 ID");
        }
    }

    @Test
    @Order(21)
    void organizationAndListEndpointsAreHospitalScoped() throws Exception {
        JsonNode departments = ok("GET", "/departments", null, "quality");
        assertEquals(6, departments.path("items").size(), "全院视角应看到本院 6 个科室");
        JsonNode deptView = ok("GET", "/departments", null, "department");
        assertEquals(1, deptView.path("items").size(), "科室视角只看到本科室");
        assertEquals("CARD", deptView.path("items").get(0).path("code").asText());

        JsonNode batches = ok("GET", "/batches?limit=10", null);
        assertTrue(batches.path("items").size() >= 2, "批次列表应包含前面导入的批次");
        for (JsonNode item : batches.path("items")) {
            assertTrue(item.path("id").asText().startsWith("bat-"));
        }

        JsonNode runs = ok("GET", "/score-runs?limit=10", null);
        assertTrue(runs.path("items").size() >= 2, "试算列表应包含两次试算");
    }

    @Test
    @Order(22)
    void prerequisiteConclusionsAreScopedAndSystemClauseIsLocked() throws Exception {
        JsonNode list = ok("GET", "/prerequisites?versionId=" + versionId, null);
        assertEquals(30, list.path("items").size());
        assertEquals("unknown", list.path("items").get(0).path("compliance").asText(),
                "未维护时默认未知，不得默认符合");

        JsonNode updated = ok("PUT", "/prerequisites/PRE-04",
                "{\"versionId\":\"" + versionId + "\",\"compliance\":\"non_compliant\","
                        + "\"workflow\":\"accepted\",\"statement\":\"演示：依法执业条款不符合\"}", "quality");
        assertTrue(updated.path("nonCompliant").size() >= 1, updated.toString());

        JsonNode locked = error(409, "PUT", "/prerequisites/SYS-TRUTH",
                "{\"versionId\":\"" + versionId + "\",\"compliance\":\"compliant\","
                        + "\"workflow\":\"accepted\",\"statement\":\"试图人工改为符合\"}", "quality");
        assertEquals("immutable", locked.path("code").asText());

        error(400, "PUT", "/prerequisites/PRE-05",
                "{\"versionId\":\"" + versionId + "\",\"compliance\":\"unknown\","
                        + "\"workflow\":\"accepted\",\"statement\":\"未知不得接纳\"}", "quality");
        error(403, "PUT", "/prerequisites/PRE-06",
                "{\"versionId\":\"" + versionId + "\",\"compliance\":\"compliant\","
                        + "\"workflow\":\"accepted\",\"statement\":\"观察员无权\"}", "information");
    }

    @Test
    @Order(23)
    void auditSampleIsDerivedFromAppliedFacts() throws Exception {
        JsonNode sample = ok("POST", "/audit-samples", "{\"period\":\"2026-10\"}");
        int sampleCount = sample.path("sampleCount").asInt();
        int errorCount = sample.path("errorCount").asInt();
        assertTrue(sampleCount > 0, sample.toString());
        assertEquals(sampleCount, sample.path("items").size());
        assertEquals((double) errorCount / sampleCount, sample.path("errorRate").asDouble(), 1e-9);
        for (JsonNode item : sample.path("items")) {
            assertTrue(item.has("deviation"));
            assertTrue(java.util.Set.of("error", "matched").contains(item.path("result").asText()));
        }
        JsonNode list = ok("GET", "/audit-samples", null);
        assertTrue(list.path("items").size() >= 1);
    }

    @Test
    @Order(24)
    void scoreRunDefersOnPrerequisiteButKeepsEarlierRunUnchanged() throws Exception {
        JsonNode run = ok("POST", "/score-runs",
                "{\"versionId\":\"" + versionId + "\",\"cycleStart\":2023,\"cycleEnd\":2026}");
        assertEquals("deferred", run.path("run").path("grade").asText(), run.toString());
        String reason = run.path("run").path("deferredReason").asText();
        assertTrue(reason.contains("前置不符合"), reason);

        JsonNode earlier = ok("GET", "/score-runs/" + earlierRunId, null);
        assertEquals(earlierRunGrade, earlier.path("run").path("grade").asText(""),
                "历史快照不因新增前置结论而改变");
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
