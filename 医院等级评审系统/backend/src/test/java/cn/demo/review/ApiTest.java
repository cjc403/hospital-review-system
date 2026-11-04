package cn.demo.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiTest {
    ConfigurableApplicationContext context;
    final HttpClient client=HttpClient.newHttpClient();
    final ObjectMapper json=new ObjectMapper();
    Path root; String base;
    @BeforeAll void start() throws Exception { root=Files.createTempDirectory("hospital-api-"); open(); }
    void open() {
        context=new SpringApplicationBuilder(DemoApplication.class).run("--server.port=0", "--review.auth.legacy-demo-headers=true", "--demo.rich-seed.enabled=false","--spring.datasource.url=jdbc:h2:file:"+root.resolve("state").toString().replace('\\','/')+";DB_CLOSE_ON_EXIT=FALSE","--demo.upload-dir="+root.resolve("uploads"),"--spring.main.banner-mode=off");
        base="http://127.0.0.1:"+context.getEnvironment().getProperty("local.server.port")+"/api";
    }
    @AfterAll void stop() { if(context!=null) context.close(); }
    @BeforeEach void reset() throws Exception { req("POST","/demo/reset","{\"confirm\":\"RESET_DEMO\"}","quality"); }
    HttpResponse<byte[]> req(String method,String path,String body,String role) throws Exception {
        var b=HttpRequest.newBuilder(URI.create(base+path)).header("X-Demo-Role",role);
        b.header("Content-Type","application/json"); b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
        return client.send(b.build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    JsonNode success(String method,String path,String body) throws Exception {
        var r=req(method,path,body,"quality"); assertEquals(200,r.statusCode(),new String(r.body(),StandardCharsets.UTF_8)); return json.readTree(r.body());
    }
    JsonNode workspace() throws Exception { return success("GET","/workspace",null); }
    JsonNode byId(JsonNode array,String id) { for(var item:array)if(id.equals(item.path("id").asText()))return item; throw new AssertionError("missing "+id); }
    String submit(String indicator,double value) throws Exception { return success("POST","/manual-values","{\"indicatorId\":\""+indicator+"\",\"deptCode\":\"ALL\",\"period\":\"2026-09\",\"value\":"+value+",\"note\":\"合成补录核对\"}").path("id").asText(); }
    void approve(String id) throws Exception { success("POST","/manual-values/"+id+"/review","{\"decision\":\"approved\",\"note\":\"通过\"}"); }
    String evidence(String type,String owner,String name,String content) throws Exception { return evidence(type,owner,name,content,200); }
    String evidence(String type,String owner,String name,String content,int expected) throws Exception {
        String boundary="demoBoundary";
        String payload="--"+boundary+"\r\nContent-Disposition: form-data; name=\"ownerType\"\r\n\r\n"+type+"\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"ownerId\"\r\n\r\n"+owner+"\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+name+"\"\r\nContent-Type: text/plain\r\n\r\n"+content+"\r\n--"+boundary+"--\r\n";
        var request=HttpRequest.newBuilder(URI.create(base+"/evidence")).header("Content-Type","multipart/form-data; boundary="+boundary).header("X-Demo-Role","quality").POST(HttpRequest.BodyPublishers.ofString(payload)).build();
        var response=client.send(request,HttpResponse.BodyHandlers.ofString()); assertEquals(expected,response.statusCode(),response.body()); return json.readTree(response.body()).path("id").asText();
    }
    @Test void workspaceContainsSyntheticSeriesAndAllPrerequisites() throws Exception {
        var w=workspace(); assertTrue(w.path("meta").path("demo").asBoolean());
        assertTrue(w.path("indicators").size()>=12); assertTrue(w.path("indicators").size()<=16); assertEquals(30,w.path("prerequisites").size());
        assertEquals(12,w.path("indicators").get(0).path("trends").size());
        for(var indicator:w.path("indicators"))if(indicator.path("unit").asText().equals("%")){
            for(var annual:indicator.path("annualValues"))assertTrue(annual.path("value").asDouble()<=100,"annual percent exceeds 100");
            for(var trend:indicator.path("trends"))assertTrue(trend.path("value").asDouble()<=100,"monthly percent exceeds 100");
        }
    }
    @Test void approvalsPreserveAutomaticValuesAndDifferentIndicatorOverridesAcrossRerun() throws Exception {
        var before=workspace(); double automatic=byId(before.path("indicators"),"i01").path("autoValue").asDouble();
        String a=submit("i01",0.12),b=submit("i02",4); approve(a); approve(b);
        success("POST","/calc-batches","{\"period\":\"2026-09\"}");
        var after=workspace(); assertEquals(0.12,byId(after.path("indicators"),"i01").path("value").asDouble());
        assertEquals(automatic,byId(after.path("indicators"),"i01").path("autoValue").asDouble());
        assertEquals(4,byId(after.path("indicators"),"i02").path("value").asDouble());
        assertEquals(409,req("POST","/manual-values/"+a+"/review","{\"decision\":\"approved\",\"note\":\"重复\"}","quality").statusCode());
    }
    @Test void backendEnforcesRolesAndDepartmentVisibility() throws Exception {
        assertEquals(403,req("GET","/indicators/i01/members",null,"leader").statusCode());
        assertEquals(403,req("POST","/calc-batches","{\"period\":\"2026-09\"}","department").statusCode());
        assertEquals(403,req("GET","/indicators/i01/members?dept=SURG",null,"department").statusCode());
        var r=req("GET","/workspace",null,"department"); assertEquals(200,r.statusCode());
        var w=json.readTree(r.body()); assertEquals(1,w.path("departments").size()); assertEquals("CARD",w.path("departments").get(0).path("code").asText());
    }
    @Test void patientsAreMaskedAndMemberTotalsMatchSelectedFacts() throws Exception {
        var i=byId(workspace().path("indicators"),"i01");
        var all=success("GET","/indicators/i01/members?dept=ALL&role=denominator&page=2&size=13",null);
        assertEquals(i.path("denominator").asInt(),all.path("total").asInt()); assertEquals(13,all.path("items").size());
        assertTrue(all.path("items").get(0).path("inpatientNo").asText().contains("****"));
        var dept=success("GET","/indicators/i01/members?dept=CARD&role=numerator",null);
        assertEquals(i.path("departmentValues").get(0).path("numerator").asInt(),dept.path("total").asInt());
        assertEquals(0,success("GET","/indicators/i12/members",null).path("total").asInt());
    }
    @Test void summaryRolesNeverReceivePatientLevelReconciliationRows() throws Exception {
        for(String actor:List.of("leader","information")) {
            var response=req("GET","/workspace",null,actor);
            assertEquals(200,response.statusCode());
            assertEquals(0,json.readTree(response.body()).path("reconciliations").size(),actor+" must not receive patient rows");
        }
        assertTrue(workspace().path("reconciliations").size() >= 48);
    }
    @Test void historicalRerunIncludesItsCurrentPointAndRetainsFutureKnownHistory() throws Exception {
        success("POST","/calc-batches","{\"period\":\"2026-10\"}");
        String manual=success("POST","/manual-values","{\"indicatorId\":\"i01\",\"deptCode\":\"ALL\",\"period\":\"2025-10\",\"value\":0.12,\"note\":\"历史周期补录\"}").path("id").asText();
        approve(manual);
        success("POST","/calc-batches","{\"period\":\"2025-10\"}");
        var old=byId(workspace().path("indicators"),"i01");
        assertEquals(0.12,old.path("value").asDouble());
        assertEquals(12,old.path("trends").size());
        assertEquals("2025-10",old.path("trends").get(11).path("period").asText());
        assertEquals(0.12,old.path("trends").get(11).path("value").asDouble());
        success("POST","/calc-batches","{\"period\":\"2026-10\"}");
        var recent=byId(workspace().path("indicators"),"i01").path("trends");
        assertEquals(12,recent.size());
        assertEquals("2025-11",recent.get(0).path("period").asText());
        assertEquals("2026-10",recent.get(11).path("period").asText());
    }
    @Test void nullAnnualFactsAreExcludedAndAllNullFactsProduceNoGrade() throws Exception {
        var repository=context.getBean(cn.demo.review.persistence.SnapshotRepository.class);
        var fixture=repository.load().orElseThrow();
        fixture.workspace.indicators.get(0).annualValues.forEach(a->a.value=null);
        repository.save(fixture); context.close(); open();
        var partial=success("POST","/score-runs","{\"cycleStart\":2023,\"cycleEnd\":2026}");
        assertEquals(13,partial.path("includedCount").asInt());
        var excluded=partial.path("items").get(0);
        assertEquals("not_applicable",excluded.path("reasonCode").asText());
        assertTrue(excluded.path("conform").isNull());
        repository=context.getBean(cn.demo.review.persistence.SnapshotRepository.class);
        fixture=repository.load().orElseThrow();
        fixture.workspace.indicators.forEach(i->i.annualValues.forEach(a->a.value=null));
        repository.save(fixture); context.close(); open();
        var empty=success("POST","/score-runs","{\"cycleStart\":2023,\"cycleEnd\":2026}");
        assertEquals(0,empty.path("includedCount").asInt());
        assertTrue(empty.path("grade").isNull());
    }
    @Test void systemTruthCannotBeEditedAndUnknownPrerequisitesWarn() throws Exception {
        assertEquals(403,req("PUT","/prerequisites/p30","{\"compliance\":\"compliant\",\"workflow\":\"accepted\",\"statement\":\"篡改\"}","quality").statusCode());
        var score=success("POST","/score-runs","{\"cycleStart\":2023,\"cycleEnd\":2026}");
        assertTrue(score.path("trial").asBoolean()); assertTrue(score.path("warnings").toString().contains("前置"));
        var sample=success("POST","/audit-samples","{\"period\":\"2026-09\"}");
        assertTrue(sample.path("errorRate").asDouble()>0.1);
        assertEquals("non_compliant",byId(workspace().path("prerequisites"),"p30").path("compliance").asText());
        assertEquals("deferred",success("POST","/score-runs","{\"cycleStart\":2023,\"cycleEnd\":2026}").path("grade").asText());
    }
    @Test void auditHistoryIsAppendOnlyAndCaseDiscrepanciesDoNotEnterClauseRate() throws Exception {
        var a=success("POST","/audit-samples","{\"period\":\"2026-09\"}");
        success("PATCH","/reconciliations/r01","{\"status\":\"explained\",\"note\":\"已核对合成病案\"}");
        var b=success("POST","/audit-samples","{\"period\":\"2026-09\"}");
        assertNotEquals(a.path("id"),b.path("id")); assertEquals(a.path("errorRate"),b.path("errorRate"));
        assertEquals(2,workspace().path("auditSamples").size());
    }
    @Test void nonComplianceNeedsEvidenceAndStatement() throws Exception {
        String body="{\"compliance\":\"non_compliant\",\"workflow\":\"submitted\",\"statement\":\"需要整改\"}";
        assertEquals(400,req("PUT","/prerequisites/p20",body,"quality").statusCode());
        evidence("prereq","p20","proof.txt","合成说明");
        success("PUT","/prerequisites/p20",body);
    }
    @Test void improvementRequiresSequentialTransitionsEvidenceAndLaterPeriodRecovery() throws Exception {
        String id=success("POST","/improvements","{\"indicatorId\":\"i01\",\"origin\":\"red_light\",\"owner\":\"质控科\",\"dueDate\":\"2026-11-30\"}").path("id").asText();
        assertEquals(409,req("PATCH","/improvements/"+id,"{\"status\":\"verified\"}","quality").statusCode());
        success("PATCH","/improvements/"+id,"{\"status\":\"assigned\"}"); success("PATCH","/improvements/"+id,"{\"status\":\"doing\",\"actionText\":\"强化合成病例培训\"}");
        assertEquals(400,req("PATCH","/improvements/"+id,"{\"status\":\"evidence_ready\"}","quality").statusCode());
        evidence("improvement",id,"result.txt","合成改进证据");
        success("PATCH","/improvements/"+id,"{\"status\":\"evidence_ready\"}"); success("PATCH","/improvements/"+id,"{\"status\":\"verified\"}");
        assertEquals(400,req("PATCH","/improvements/"+id,"{\"status\":\"closed\",\"closeMode\":\"improved\"}","quality").statusCode());
        success("POST","/calc-batches","{\"period\":\"2026-10\"}");
        var closed=success("PATCH","/improvements/"+id,"{\"status\":\"closed\",\"closeMode\":\"improved\"}"); assertEquals("closed",closed.path("status").asText());
        assertEquals(403,req("PATCH","/improvements/"+id,"{\"status\":\"reopen\"}","department").statusCode());
        assertEquals("opened",success("PATCH","/improvements/"+id,"{\"status\":\"reopen\"}").path("status").asText());
    }
    @Test void reportsRemainBoundToScoreAndPrerequisiteSnapshots() throws Exception {
        String id=success("POST","/score-runs","{\"cycleStart\":2023,\"cycleEnd\":2026}").path("id").asText();
        var before=req("GET","/reports/"+id,null,"quality"); assertEquals(200,before.statusCode());
        evidence("prereq","p01","issue.txt","问题证据"); success("PUT","/prerequisites/p01","{\"compliance\":\"non_compliant\",\"workflow\":\"submitted\",\"statement\":\"报告后新发现问题\"}");
        approve(submit("i01",99));
        var after=req("GET","/reports/"+id,null,"quality"); assertArrayEquals(before.body(),after.body());
        var entries=new HashMap<String,String>(); try(var z=new ZipInputStream(new ByteArrayInputStream(after.body()))){for(ZipEntry e;(e=z.getNextEntry())!=null;)entries.put(e.getName(),new String(z.readAllBytes(),StandardCharsets.UTF_8));}
        assertTrue(entries.containsKey("[Content_Types].xml")); assertTrue(entries.get("word/document.xml").contains("演示试算")); assertFalse(entries.get("word/document.xml").contains("报告后新发现问题"));
    }
    @Test void evidenceBytesRoundTripAndUnsupportedFilesAreRejected() throws Exception {
        String id=evidence("prereq","p01","safe.txt","synthetic content");
        var r=req("GET","/evidence/"+id+"/download",null,"quality"); assertEquals(200,r.statusCode()); assertEquals("synthetic content",new String(r.body(),StandardCharsets.UTF_8));
        assertEquals(404,req("GET","/evidence/no-such-file/download",null,"quality").statusCode());
        evidence("prereq","p01","bad.svg","<svg/>",400);
        evidence("prereq","p01","pretend.pdf","not pdf",400);
    }
    @Test void scoringPolicyCannotBeConfirmedAndOtherPoliciesCan() throws Exception {
        String id=success("POST","/policies","{\"code\":\"DEMO-NEW\",\"title\":\"试算规则草案\",\"kind\":\"reference\",\"publishedOn\":\"2026-10-01\",\"effectiveOn\":\"2026-11-01\",\"impacts\":[\"scoring_rule\"],\"note\":\"演示\"}").path("id").asText();
        assertEquals(409,req("POST","/policies/"+id+"/confirm","{}","quality").statusCode());
        assertEquals("blocked",byId(workspace().path("policies"),id).path("status").asText());
    }
    @Test void policyEnumerationsAreCheckedAndInformationMayOnlyRegister() throws Exception {
        String valid="{\"code\":\"DEMO-ENUM\",\"title\":\"口径登记\",\"kind\":\"reference\",\"publishedOn\":\"2026-10-01\",\"effectiveOn\":\"2026-11-01\",\"impacts\":[\"indicator_def\"],\"note\":\"演示\"}";
        assertEquals(400,req("POST","/policies",valid.replace("reference","notice"),"quality").statusCode());
        assertEquals(400,req("POST","/policies",valid.replace("indicator_def","unknown"),"quality").statusCode());
        var r=req("POST","/policies",valid,"information"); assertEquals(200,r.statusCode());String id=json.readTree(r.body()).path("id").asText();
        assertEquals(403,req("POST","/policies/"+id+"/confirm","{}","information").statusCode());
        assertEquals("confirmed",success("POST","/policies/"+id+"/confirm","{}").path("status").asText());
    }
    @Test void latestSampleAtExactlyTenPercentClearsOnlySystemTruthConclusion() throws Exception {
        success("POST","/audit-samples","{\"period\":\"2026-09\"}");
        var next=success("POST","/audit-samples","{\"period\":\"2026-10\"}");assertEquals(0.1,next.path("errorRate").asDouble());
        assertEquals("compliant",byId(workspace().path("prerequisites"),"p30").path("compliance").asText());
        assertNotEquals("deferred",success("POST","/score-runs","{\"cycleStart\":2023,\"cycleEnd\":2026}").path("grade").asText());
        assertEquals(2,workspace().path("auditSamples").size());
    }
    @Test void twoConsecutiveWorseningMonthsTriggerRedEvenAboveTarget() throws Exception {
        var i=byId(workspace().path("indicators"),"i09");assertTrue(i.path("value").asDouble()>=95);
        assertEquals("red",i.path("light").asText());
    }
    @Test void filesCannotMutateClosedImprovementWithoutReopening() throws Exception {
        String id=success("POST","/improvements","{\"indicatorId\":\"i01\",\"origin\":\"red_light\",\"owner\":\"质控科\",\"dueDate\":\"2026-11-30\"}").path("id").asText();
        success("PATCH","/improvements/"+id,"{\"status\":\"assigned\"}");success("PATCH","/improvements/"+id,"{\"status\":\"doing\",\"actionText\":\"合成培训\"}");evidence("improvement",id,"first.txt","证据");
        success("PATCH","/improvements/"+id,"{\"status\":\"evidence_ready\"}");success("PATCH","/improvements/"+id,"{\"status\":\"verified\"}");
        success("PATCH","/improvements/"+id,"{\"status\":\"closed\",\"closeMode\":\"not_recovered\",\"closeNote\":\"尚未进入后续周期，继续跟踪\"}");
        evidence("improvement",id,"late.txt","关闭后添加",409);
        assertEquals(1,byId(workspace().path("improvements"),id).path("evidenceCount").asInt());
    }
    @Test void malformedRequestsAndMemberBoundariesReturnContractErrors() throws Exception {
        assertEquals(400,req("GET","/indicators/i01/members?page=0",null,"quality").statusCode());
        assertEquals(400,req("GET","/indicators/i01/members?role=anything",null,"quality").statusCode());
        assertEquals(403,req("GET","/workspace",null,"unknown").statusCode());
        assertEquals(400,req("POST","/manual-values","{bad json","quality").statusCode());
        assertEquals(404,req("GET","/indicators/nonexistent/members",null,"quality").statusCode());
        assertEquals(0,success("GET","/indicators/i01/members?page=2147483647&size=100",null).path("items").size());
    }
    @Test void validationReturnsContractErrorsAndPersistenceSurvivesReopen() throws Exception {
        assertEquals(400,req("POST","/manual-values","{\"indicatorId\":\"i01\",\"value\":3}","quality").statusCode());
        var r=req("POST","/score-runs","{\"cycleStart\":2020,\"cycleEnd\":2026}","quality"); assertEquals(400,r.statusCode()); assertTrue(json.readTree(r.body()).has("message"));
        var id=submit("i01",0.21); approve(id); context.close(); open();
        assertEquals(0.21,byId(workspace().path("indicators"),"i01").path("value").asDouble());
        assertTrue(workspace().path("auditLogs").size()>=2);
    }
}
