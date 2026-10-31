package cn.demo.review.v3.web;

import cn.demo.review.v2.quality.QualityService;
import cn.demo.review.v2.security.AuthContext;
import cn.demo.review.v2.security.AuthFilter;
import cn.demo.review.v3.alert.AlertService;
import cn.demo.review.v3.ingestion.IngestionService;
import cn.demo.review.v3.metric.MetricDefinitionService;
import cn.demo.review.v3.report.ReportService;
import cn.demo.review.v3.result.ResultService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** v3 接口入口。认证、租户与角色范围沿用 v2 的 AuthFilter 与 AuthContext。 */
@RestController
@RequestMapping("/api/v3")
public class V3Controller {
    private final MetricDefinitionService definitions;
    private final IngestionService ingestion;
    private final ResultService results;
    private final AlertService alerts;
    private final ReportService reports;
    private final QualityService quality;

    public V3Controller(MetricDefinitionService definitions, IngestionService ingestion, ResultService results,
                        AlertService alerts, ReportService reports, QualityService quality) {
        this.definitions = definitions;
        this.ingestion = ingestion;
        this.results = results;
        this.alerts = alerts;
        this.reports = reports;
        this.quality = quality;
    }

    // ---------- 指标定义 ----------

    @GetMapping("/metric-definitions")
    public Map<String, Object> metricDefinitions(HttpServletRequest request,
                                                 @RequestParam(required = false) String versionId) {
        return definitions.list(AuthFilter.current(request), versionId);
    }

    @PostMapping("/metric-definitions")
    public Map<String, Object> createDefinition(HttpServletRequest request, @RequestBody JsonNode body) {
        return definitions.create(AuthFilter.current(request), body, requestId(request));
    }

    // ---------- 数据接入 ----------

    @GetMapping("/data-sources")
    public Map<String, Object> dataSources(HttpServletRequest request) {
        return quality.sources(AuthFilter.current(request));
    }

    @GetMapping("/source-mappings")
    public Map<String, Object> mappings(HttpServletRequest request,
                                        @RequestParam(required = false) String sourceCode) {
        return ingestion.mappings(AuthFilter.current(request), sourceCode);
    }

    @PutMapping("/source-mappings")
    public Map<String, Object> saveMapping(HttpServletRequest request, @RequestBody JsonNode body) {
        return ingestion.saveMapping(AuthFilter.current(request), body, requestId(request));
    }

    @PostMapping("/ingestion/batches")
    public Map<String, Object> createBatch(HttpServletRequest request, @RequestBody JsonNode body) {
        return ingestion.createBatch(AuthFilter.current(request), body, requestId(request));
    }

    @GetMapping("/ingestion/batches")
    public Map<String, Object> listBatches(HttpServletRequest request,
                                           @RequestParam(defaultValue = "20") int limit) {
        return Map.of("items", ingestion.listBatches(AuthFilter.current(request), limit));
    }

    @GetMapping("/ingestion/batches/{batchId}")
    public Map<String, Object> batch(HttpServletRequest request, @PathVariable String batchId) {
        return ingestion.detail(AuthFilter.current(request), batchId);
    }

    @PostMapping("/ingestion/batches/{batchId}/apply")
    public Map<String, Object> applyBatch(HttpServletRequest request, @PathVariable String batchId) {
        return ingestion.apply(AuthFilter.current(request), batchId, requestId(request));
    }

    @PostMapping("/ingestion/batches/{batchId}/replay")
    public Map<String, Object> replayBatch(HttpServletRequest request, @PathVariable String batchId) {
        return ingestion.replay(AuthFilter.current(request), batchId, requestId(request));
    }

    // ---------- 结果与血缘 ----------

    @GetMapping("/metric-results")
    public Map<String, Object> metricResults(HttpServletRequest request,
                                             @RequestParam(required = false) String metricCode,
                                             @RequestParam(required = false) String period,
                                             @RequestParam(required = false) String deptCode,
                                             @RequestParam(required = false) String versionId,
                                             @RequestParam(required = false) String chapterCode,
                                             @RequestParam(defaultValue = "100") int limit) {
        return results.list(AuthFilter.current(request), metricCode, period, deptCode, versionId, chapterCode, limit);
    }

    @GetMapping("/metric-results/{id}")
    public Map<String, Object> metricResult(HttpServletRequest request, @PathVariable String id) {
        return results.get(AuthFilter.current(request), id);
    }

    @GetMapping("/metric-results/{id}/drilldown")
    public Map<String, Object> drilldown(HttpServletRequest request, @PathVariable String id) {
        return results.drilldown(AuthFilter.current(request), id);
    }

    @GetMapping("/lineage/results/{resultId}")
    public Map<String, Object> lineage(HttpServletRequest request, @PathVariable String resultId) {
        return results.lineage(AuthFilter.current(request), resultId);
    }

    // ---------- 预警 ----------

    @GetMapping("/alerts")
    public Map<String, Object> alertList(HttpServletRequest request,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String severity,
                                         @RequestParam(defaultValue = "100") int limit) {
        return alerts.listAlerts(AuthFilter.current(request), status, severity, limit);
    }

    @PostMapping("/alerts/{id}/ack")
    public Map<String, Object> ack(HttpServletRequest request, @PathVariable String id,
                                   @RequestBody(required = false) JsonNode body) {
        return alerts.transition(AuthFilter.current(request), id, "ack", body, requestId(request));
    }

    @PostMapping("/alerts/{id}/assign")
    public Map<String, Object> assign(HttpServletRequest request, @PathVariable String id,
                                      @RequestBody JsonNode body) {
        return alerts.transition(AuthFilter.current(request), id, "assign", body, requestId(request));
    }

    @PostMapping("/alerts/{id}/resolve")
    public Map<String, Object> resolve(HttpServletRequest request, @PathVariable String id,
                                       @RequestBody(required = false) JsonNode body) {
        return alerts.transition(AuthFilter.current(request), id, "resolve", body, requestId(request));
    }

    @PostMapping("/alerts/{id}/ignore")
    public Map<String, Object> ignore(HttpServletRequest request, @PathVariable String id,
                                      @RequestBody(required = false) JsonNode body) {
        return alerts.transition(AuthFilter.current(request), id, "ignore", body, requestId(request));
    }

    // ---------- 整改 ----------

    @PostMapping("/improvements/from-alert")
    public Map<String, Object> improvementFromAlert(HttpServletRequest request, @RequestBody JsonNode body) {
        return alerts.createFromAlert(AuthFilter.current(request), body, requestId(request));
    }

    @PostMapping("/improvements")
    public Map<String, Object> improvementFromSource(HttpServletRequest request, @RequestBody JsonNode body) {
        return alerts.createFromSource(AuthFilter.current(request), body, requestId(request));
    }

    @GetMapping("/improvements")
    public Map<String, Object> improvements(HttpServletRequest request,
                                            @RequestParam(required = false) String status) {
        return alerts.listImprovements(AuthFilter.current(request), status);
    }

    @GetMapping("/improvements/{id}")
    public Map<String, Object> improvement(HttpServletRequest request, @PathVariable String id) {
        return alerts.improvement(AuthFilter.current(request), id);
    }

    @PostMapping("/improvements/{id}/review")
    public Map<String, Object> reviewImprovement(HttpServletRequest request, @PathVariable String id,
                                                 @RequestBody JsonNode body) {
        return alerts.reviewImprovement(AuthFilter.current(request), id, body, requestId(request));
    }

    // ---------- 报告 ----------

    @PostMapping("/reports")
    public Map<String, Object> createReport(HttpServletRequest request, @RequestBody JsonNode body) {
        return reports.create(AuthFilter.current(request), body, requestId(request));
    }

    @GetMapping("/reports")
    public Map<String, Object> reportList(HttpServletRequest request,
                                         @RequestParam(defaultValue = "20") int limit) {
        return Map.of("items", reports.list(AuthFilter.current(request), limit));
    }

    @GetMapping("/reports/{id}")
    public Map<String, Object> report(HttpServletRequest request, @PathVariable String id) {
        return reports.get(AuthFilter.current(request), id);
    }

    @GetMapping("/reports/{id}/document")
    public ResponseEntity<byte[]> document(HttpServletRequest request, @PathVariable String id) throws Exception {
        byte[] bytes = reports.document(AuthFilter.current(request), id, requestId(request));
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename("v3-report-" + id + ".docx", StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .body(bytes);
    }

    private static String requestId(HttpServletRequest request) {
        Object id = request.getAttribute("review.requestId");
        return id == null ? "-" : id.toString();
    }

    @SuppressWarnings("unused")
    private static AuthContext current(HttpServletRequest request) {
        return AuthFilter.current(request);
    }
}
