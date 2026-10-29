package cn.demo.review.web;

import cn.demo.review.domain.Model.*;
import cn.demo.review.service.DemoService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final DemoService service;
    public ApiController(DemoService service){this.service=service;}
    @GetMapping("/health") public Object health(){return Map.of("status","ok","demo",true);}
    @GetMapping("/workspace") public Workspace workspace(@RequestHeader(value="X-Demo-Role",defaultValue="quality")String role){return service.workspace(role);}
    @GetMapping("/indicators/{id}/members") public MemberPage members(@RequestHeader(value="X-Demo-Role",defaultValue="quality")String role,@PathVariable String id,@RequestParam(defaultValue="ALL")String dept,@RequestParam(name="role",defaultValue="denominator")String memberRole,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){return service.members(role,id,dept,memberRole,page,size);}
    @PostMapping("/manual-values") public Manual manual(@RequestHeader(value="X-Demo-Role",defaultValue="quality")String role,@RequestBody JsonNode b){return service.submitManual(role,b);}
    @PostMapping("/manual-values/{id}/review") public Manual review(@RequestHeader(value="X-Demo-Role",defaultValue="quality")String role,@PathVariable String id,@RequestBody JsonNode b){return service.reviewManual(role,id,b);}
    @PatchMapping("/reconciliations/{id}") public Reconcile reconcile(@RequestHeader(value="X-Demo-Role",defaultValue="quality")String role,@PathVariable String id,@RequestBody JsonNode b){return service.reconcile(role,id,b);}
    @PostMapping("/audit-samples") public AuditSample sample(@RequestHeader(value="X-Demo-Role",defaultValue="quality")String role,@RequestBody JsonNode b){return service.sample(role,b);}
    @PutMapping("/prerequisites/{id}") public Prereq prereq(@RequestHeader(value="X-Demo-Role",defaultValue="quality")String role,@PathVariable String id,@RequestBody JsonNode b){return service.updatePrereq(role,id,b);}
    @PostMapping("/score-runs") public ScoreRun score(@RequestHeader(value="X-Demo-Role",defaultValue="quality")String role,@RequestBody JsonNode b){return service.score(role,b);}
    @PostMapping("/score-runs/{id}/adopt") public ScoreRun adopt(@RequestHeader(value="X-Demo-Role",defaultValue="quality")String role,@PathVariable String id){return service.adopt(role,id);}
    @PostMapping("/improvements") public Improvement improvement(@RequestHeader(value="X-Demo-Role",defaultValue="quality")String role,@RequestBody JsonNode b){return service.createImprovement(role,b);}
    @PatchMapping("/improvements/{id}") public Improvement advance(@RequestHeader(value="X-Demo-Role",defaultValue="quality")String role,@PathVariable String id,@RequestBody JsonNode b){return service.updateImprovement(role,id,b);}
    @PostMapping("/policies") public Policy policy(@RequestHeader(value="X-Demo-Role",defaultValue="quality")String role,@RequestBody JsonNode b){return service.registerPolicy(role,b);}
    @PostMapping("/policies/{id}/confirm") public Policy confirm(@RequestHeader(value="X-Demo-Role",defaultValue="quality")String role,@PathVariable String id){return service.confirmPolicy(role,id);}
    @PostMapping("/calc-batches") public Batch calculate(@RequestHeader(value="X-Demo-Role",defaultValue="quality")String role,@RequestBody JsonNode b){return service.calculate(role,b);}
    @PostMapping(value="/evidence",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) public Evidence evidence(@RequestHeader(value="X-Demo-Role",defaultValue="quality")String role,@RequestParam String ownerType,@RequestParam String ownerId,@RequestParam MultipartFile file)throws IOException{return service.upload(role,ownerType,ownerId,file);}
    @GetMapping("/evidence/{id}/download") public ResponseEntity<byte[]> download(@RequestHeader(value="X-Demo-Role",defaultValue="quality")String role,@PathVariable String id)throws IOException{return response(service.download(role,id));}
    @GetMapping("/reports/{id}") public ResponseEntity<byte[]> report(@RequestHeader(value="X-Demo-Role",defaultValue="quality")String role,@PathVariable String id)throws IOException{return response(service.report(role,id));}
    @PostMapping("/demo/reset") public Object reset(@RequestHeader(value="X-Demo-Role",defaultValue="quality")String role,@RequestBody JsonNode b){return service.reset(role,b);}
    private static ResponseEntity<byte[]> response(DemoService.Download d){return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(d.filename(),StandardCharsets.UTF_8).build().toString()).header("X-Content-Type-Options","nosniff").header(HttpHeaders.CACHE_CONTROL,"no-store").contentType(MediaType.parseMediaType(d.contentType())).body(d.bytes());}
}
