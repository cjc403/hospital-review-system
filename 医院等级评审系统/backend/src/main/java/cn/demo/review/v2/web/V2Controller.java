package cn.demo.review.v2.web;

import cn.demo.review.v2.evidence.EvidenceService;
import cn.demo.review.v2.platform.AuditService;
import cn.demo.review.v2.platform.OrganizationService;
import cn.demo.review.v2.quality.QualityService;
import cn.demo.review.v2.score.ScoreService;
import cn.demo.review.v2.security.AuthContext;
import cn.demo.review.v2.security.AuthFilter;
import cn.demo.review.v2.standard.StandardService;
import cn.demo.review.v2.task.TaskService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** v0.2 接口入口。认证与范围校验在 AuthFilter 与各服务内完成，控制器不做授权判断。 */
@RestController
@RequestMapping("/api/v2")
public class V2Controller {
    private final StandardService standards;
    private final TaskService tasks;
    private final EvidenceService evidence;
    private final QualityService quality;
    private final ScoreService scores;
    private final AuditService audit;
    private final OrganizationService organization;

    public V2Controller(StandardService standards, TaskService tasks, EvidenceService evidence,
                        QualityService quality, ScoreService scores, AuditService audit,
                        OrganizationService organization) {
        this.standards = standards;
        this.tasks = tasks;
        this.evidence = evidence;
        this.quality = quality;
        this.scores = scores;
        this.audit = audit;
        this.organization = organization;
    }

    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest request) {
        return AuthFilter.current(request).describe();
    }

    @GetMapping("/departments")
    public Map<String, Object> departments(HttpServletRequest request) {
        return organization.departments(AuthFilter.current(request));
    }

    // ---------- 标准 ----------

    @GetMapping("/standards")
    public Map<String, Object> standards() { return standards.listPackages(); }

    @GetMapping("/standards/versions/{versionId}")
    public Map<String, Object> version(@PathVariable String versionId) {
        return standards.versionDetail(versionId);
    }

    @PostMapping("/standards/import")
    public Map<String, Object> importPack(HttpServletRequest request, @RequestBody JsonNode body) {
        AuthContext ctx = AuthFilter.current(request);
        ctx.require("review_office");
        return standards.importPack(ctx, body, requestId(request));
    }

    @PostMapping("/standards/versions/{versionId}/publish")
    public Map<String, Object> publish(HttpServletRequest request, @PathVariable String versionId) {
        AuthContext ctx = AuthFilter.current(request);
        ctx.require("review_office");
        return standards.publish(ctx, versionId, requestId(request));
    }

    @PostMapping("/standards/versions/{versionId}/revision")
    public Map<String, Object> revision(HttpServletRequest request, @PathVariable String versionId,
                                       @RequestBody(required = false) JsonNode body) {
        AuthContext ctx = AuthFilter.current(request);
        ctx.require("review_office");
        String note = body == null ? null : body.path("note").asText("");
        return standards.revision(ctx, versionId, requestId(request), note);
    }

    // ---------- 任务 ----------

    @GetMapping("/tasks")
    public Map<String, Object> tasks(HttpServletRequest request,
                                     @RequestParam(required = false) String dept,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(required = false, defaultValue = "false") boolean overdue) {
        return tasks.list(AuthFilter.current(request), dept, status, overdue);
    }

    @PostMapping("/tasks/assign")
    public Map<String, Object> assign(HttpServletRequest request, @RequestBody JsonNode body) {
        return tasks.assign(AuthFilter.current(request), body, requestId(request));
    }

    @PatchMapping("/tasks/{taskId}")
    public Map<String, Object> updateTask(HttpServletRequest request, @PathVariable String taskId,
                                          @RequestBody JsonNode body) {
        return tasks.update(AuthFilter.current(request), taskId, body, requestId(request));
    }

    // ---------- 证据 ----------

    @PostMapping(value = "/evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(HttpServletRequest request,
                                      @RequestParam MultipartFile file,
                                      @RequestParam String ownerType,
                                      @RequestParam String ownerId,
                                      @RequestParam String title,
                                      @RequestParam(required = false) String materialType,
                                      @RequestParam(required = false) String validUntil) {
        return evidence.upload(AuthFilter.current(request), file, ownerType, ownerId, title,
                materialType == null ? "制度文件" : materialType, validUntil, requestId(request));
    }

    @GetMapping("/evidence")
    public Map<String, Object> evidenceList(HttpServletRequest request,
                                            @RequestParam(required = false) String ownerType,
                                            @RequestParam(required = false) String ownerId) {
        return evidence.list(AuthFilter.current(request), ownerType, ownerId);
    }

    @GetMapping("/evidence/{itemId}")
    public Map<String, Object> evidenceDetail(HttpServletRequest request, @PathVariable String itemId) {
        return evidence.detail(AuthFilter.current(request), itemId);
    }

    @PostMapping("/evidence/{itemId}/review")
    public Map<String, Object> reviewEvidence(HttpServletRequest request, @PathVariable String itemId,
                                              @RequestBody JsonNode body) {
        return evidence.review(AuthFilter.current(request), itemId,
                body.path("decision").asText(""), body.path("note").asText(""), requestId(request));
    }

    @GetMapping("/evidence/versions/{versionId}/download")
    public ResponseEntity<byte[]> downloadEvidence(HttpServletRequest request, @PathVariable String versionId) {
        EvidenceService.Download file = evidence.download(AuthFilter.current(request), versionId, requestId(request));
        return attachment(file.fileName(),
                file.contentType() == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : file.contentType(),
                file.bytes());
    }

    // ---------- 数据质量 ----------

    @GetMapping("/data-sources")
    public Map<String, Object> sources(HttpServletRequest request) {
        return quality.sources(AuthFilter.current(request));
    }

    @PostMapping("/batches")
    public Map<String, Object> createBatch(HttpServletRequest request, @RequestBody JsonNode body) {
        return quality.createBatch(AuthFilter.current(request), body, requestId(request));
    }

    @GetMapping("/batches")
    public Map<String, Object> listBatches(HttpServletRequest request,
                                          @RequestParam(defaultValue = "30") int limit) {
        return quality.list(AuthFilter.current(request), limit);
    }

    @GetMapping("/batches/{batchId}")
    public Map<String, Object> batch(HttpServletRequest request, @PathVariable String batchId) {
        return quality.detail(AuthFilter.current(request), batchId);
    }

    @PostMapping("/batches/{batchId}/apply")
    public Map<String, Object> applyBatch(HttpServletRequest request, @PathVariable String batchId) {
        return quality.apply(AuthFilter.current(request), batchId, requestId(request));
    }

    @PostMapping("/batches/{batchId}/replay")
    public Map<String, Object> replayBatch(HttpServletRequest request, @PathVariable String batchId) {
        return quality.replay(AuthFilter.current(request), batchId, requestId(request));
    }

    // ---------- 试算与报告 ----------

    @PostMapping("/score-runs")
    public Map<String, Object> createRun(HttpServletRequest request, @RequestBody JsonNode body) {
        return scores.create(AuthFilter.current(request), body, requestId(request));
    }

    @GetMapping("/score-runs")
    public Map<String, Object> listRuns(HttpServletRequest request,
                                        @RequestParam(defaultValue = "20") int limit) {
        return scores.list(AuthFilter.current(request), limit);
    }

    @GetMapping("/score-runs/{runId}")
    public Map<String, Object> run(HttpServletRequest request, @PathVariable String runId) {
        return scores.get(AuthFilter.current(request), runId);
    }

    @GetMapping("/score-runs/{runId}/report")
    public ResponseEntity<byte[]> report(HttpServletRequest request, @PathVariable String runId) throws Exception {
        byte[] bytes = scores.report(AuthFilter.current(request), runId, requestId(request));
        return attachment("review-report-" + runId + ".docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", bytes);
    }

    @GetMapping("/score-runs/{runId}/compare/{otherId}")
    public Map<String, Object> compare(HttpServletRequest request, @PathVariable String runId,
                                       @PathVariable String otherId) {
        return scores.compare(AuthFilter.current(request), runId, otherId);
    }

    // ---------- 审计 ----------

    @GetMapping("/audit-events")
    public Map<String, Object> audit(HttpServletRequest request,
                                     @RequestParam(defaultValue = "50") int limit) {
        AuthContext ctx = AuthFilter.current(request);
        ctx.require("review_office", "leader");
        return Map.of("items", audit.list(ctx.hospitalId(), limit));
    }

    private static String requestId(HttpServletRequest request) {
        Object id = request.getAttribute("review.requestId");
        return id == null ? "-" : id.toString();
    }

    private static ResponseEntity<byte[]> attachment(String fileName, String contentType, byte[] bytes) {
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(fileName, StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType(contentType))
                .body(bytes);
    }
}
