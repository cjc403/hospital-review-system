package cn.demo.review;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthApiTest {
    ConfigurableApplicationContext app;
    final HttpClient client=HttpClient.newHttpClient();
    final ObjectMapper json=new ObjectMapper();
    String base;
    final String password="cjc2003822cjc";
    @BeforeAll void open() throws Exception {
        Path root=Files.createTempDirectory("review-auth-");
        app=new SpringApplicationBuilder(DemoApplication.class).run("--server.port=0","--demo.rich-seed.enabled=false",
            "--spring.datasource.url=jdbc:h2:mem:auth-"+UUID.randomUUID(),"--demo.upload-dir="+root.resolve("uploads"),
            "--review.storage-dir="+root.resolve("evidence"),"--spring.main.banner-mode=off");
        base="http://127.0.0.1:"+app.getEnvironment().getProperty("local.server.port")+"/api";
    }
    @AfterAll void close(){if(app!=null)app.close();}
    HttpResponse<String> call(String method,String path,String token,Object body) throws Exception {
        var b=HttpRequest.newBuilder(URI.create(base+path)).header("Content-Type","application/json");
        if(token!=null)b.header("Authorization","Bearer "+token);
        b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return client.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode ok(String method,String path,String token,Object body) throws Exception {
        var r=call(method,path,token,body);assertEquals(200,r.statusCode(),r.body());return json.readTree(r.body());
    }
    String login(String name) throws Exception {return ok("POST","/auth/login",null,Map.of("username",name,"password",password)).path("token").asText();}
    @Test void everyBusinessVersionRequiresLoginAndRoleHeaderCannotBypass() throws Exception {
        for(String path:List.of("/workspace","/v2/tasks","/v3/metric-results"))assertEquals(401,call("GET",path,null,null).statusCode());
        var request=HttpRequest.newBuilder(URI.create(base+"/workspace")).header("X-Demo-Role","quality").GET().build();
        assertEquals(401,client.send(request,HttpResponse.BodyHandlers.ofString()).statusCode());
    }
    @Test void allSyntheticAccountsLoginWithUserSpecifiedPasswordAndNoHashesLeak() throws Exception {
        var items=ok("GET","/auth/demo-accounts",null,null).path("items");assertTrue(items.size()>=30);
        Set<String> roles=new HashSet<>();
        for(var item:items){
            String token=login(item.path("username").asText());assertFalse(token.isBlank());
            var me=ok("GET","/auth/me",token,null);roles.add(me.path("roleCode").asText());
            assertEquals(item.path("hospitalId").asText(),me.path("hospitalId").asText());
            assertTrue(me.path("permissions").isArray());assertFalse(me.has("passwordHash"));assertFalse(me.has("password"));
            ok("POST","/auth/logout",token,Map.of());assertEquals(401,call("GET","/auth/me",token,null).statusCode());
        }
        assertEquals(9,roles.size());
        String hashes=app.getBean(JdbcTemplate.class).queryForObject("SELECT password_hash FROM auth_account WHERE username='review.admin'",String.class);
        assertNotEquals(password,hashes);assertFalse(hashes.contains(password));
    }
    @Test void accountDisableRoleChangeAndPasswordChangeInvalidateExistingSessions() throws Exception {
        String admin=login("sys.admin");
        var user=ok("POST","/auth/accounts",admin,Map.of("username","test.auth.worker","displayName","测试联络员（合成）","roleCode","dept_liaison","deptCode","SURG"));
        String id=user.path("id").asText(), old=login("test.auth.worker");
        ok("PATCH","/auth/accounts/"+id,admin,Map.of("status","disabled"));assertEquals(401,call("GET","/auth/me",old,null).statusCode());
        ok("PATCH","/auth/accounts/"+id,admin,Map.of("status","active"));assertEquals(401,call("GET","/auth/me",old,null).statusCode());
        old=login("test.auth.worker");ok("PATCH","/auth/accounts/"+id,admin,Map.of("roleCode","dept_head"));assertEquals(401,call("GET","/auth/me",old,null).statusCode());
        String current=login("test.auth.worker");ok("POST","/auth/password",current,Map.of("currentPassword",password,"newPassword","new-demo-password-42"));
        assertEquals(401,call("GET","/auth/me",current,null).statusCode());assertEquals(401,call("POST","/auth/login",null,Map.of("username","test.auth.worker","password",password)).statusCode());
        ok("POST","/auth/accounts/"+id+"/reset-password",admin,Map.of());assertFalse(login("test.auth.worker").isBlank());
    }
    @Test void managementCannotCrossHospitalOrDelegateToOrdinaryRoles() throws Exception {
        String admin=login("sys.admin"), otherAdmin=login("h002.sys.admin");
        var other=ok("GET","/auth/accounts",otherAdmin,null).path("items").get(0);
        assertEquals(404,call("PATCH","/auth/accounts/"+other.path("id").asText(),admin,Map.of("status","disabled")).statusCode());
        String staff=login("card.staff");assertEquals(403,call("GET","/auth/accounts",staff,null).statusCode());
        var me=ok("GET","/auth/me",admin,null);
        assertEquals(409,call("PATCH","/auth/accounts/"+me.path("id").asText(),admin,Map.of("status","disabled")).statusCode());
        assertEquals(403,call("POST","/v2/standards/import",admin,Map.of()).statusCode());
    }
    @Test void scopesApplyToLegacyTasksAndExpertsAndWrites() throws Exception {
        String office=login("review.admin"), head=login("surg.director"), staff=login("surg.staff"), expert=login("expert.demo");
        var workspace=ok("GET","/workspace",head,null);
        assertEquals("SURG",workspace.path("departments").get(0).path("code").asText());
        assertEquals(1,workspace.path("departments").size());
        assertTrue(ok("GET","/workspace",login("h002.leader"),null).path("indicators").isEmpty());
        String version=ok("GET","/v2/standards",office,null).path("packages").get(0).path("versions").get(0).path("id").asText();
        var assigned=ok("POST","/v2/tasks/assign",office,Map.of("versionId",version,"deptCode","SURG","clauseCodes",List.of("CL-2.1"),"dueDate","2026-12-31","requestKey","auth-surg"));
        String task=assigned.path("tasks").get(0).path("id").asText();
        ok("PATCH","/v2/tasks/"+task,staff,Map.of("status","in_progress"));
        ok("PATCH","/v2/tasks/"+task,staff,Map.of("status","submitted"));
        assertEquals(403,call("PATCH","/v2/tasks/"+task,staff,Map.of("status","accepted")).statusCode());
        ok("PATCH","/v2/tasks/"+task,head,Map.of("status","accepted"));
        for(var row:ok("GET","/v2/tasks",expert,null).path("items")) {
            assertEquals("CARD",row.path("deptCode").asText());
            assertTrue(Set.of("CL-2.1","CL-2.2").contains(row.path("clauseCode").asText()));
        }
        assertEquals(403,call("POST","/v3/ingestion/batches",staff,Map.of()).statusCode());
        assertEquals(403,call("POST","/v2/tasks/assign",login("data.admin"),Map.of()).statusCode());
        assertEquals(200,call("GET","/v3/ingestion/batches",login("data.admin"),null).statusCode());
    }

    HttpResponse<String> upload(String token,String task) throws Exception {
        String boundary="auth-test-boundary";
        String data="--"+boundary+"\r\nContent-Disposition: form-data; name=\"ownerType\"\r\n\r\ntask\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"ownerId\"\r\n\r\n"+task+"\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"title\"\r\n\r\nAuth scope material\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"proof.txt\"\r\nContent-Type: text/plain\r\n\r\nSynthetic evidence\r\n--"+boundary+"--\r\n";
        return client.send(HttpRequest.newBuilder(URI.create(base+"/v2/evidence")).header("Authorization","Bearer "+token).header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofString(data)).build(),HttpResponse.BodyHandlers.ofString());
    }
    String assign(String office,String dept,String clause) throws Exception {
        String version=ok("GET","/v2/standards",office,null).path("packages").get(0).path("versions").get(0).path("id").asText();
        return ok("POST","/v2/tasks/assign",office,Map.of("versionId",version,"deptCode",dept,"clauseCodes",List.of(clause),"dueDate","2026-12-31","requestKey",UUID.randomUUID().toString())).path("tasks").get(0).path("id").asText();
    }
    @Test void expertsAndDepartmentsCannotReadReviewOrDownloadOtherEvidence() throws Exception {
        String office=login("review.admin"),surg=login("surg.staff"),expert=login("expert.demo");
        String allowed=assign(office,"CARD","CL-2.1"),otherClause=assign(office,"CARD","CL-3.1"),otherDept=assign(office,"SURG","CL-2.1");
        for(String task:List.of(allowed,otherClause,otherDept)) {
            var uploaded=upload(office,task);assertEquals(200,uploaded.statusCode(),uploaded.body());var e=json.readTree(uploaded.body());
            String item=e.path("item").path("id").asText(),v=e.path("version").path("id").asText();
            int expected=task.equals(allowed)?200:404;
            assertEquals(expected,call("GET","/v2/evidence/"+item,expert,null).statusCode());
            assertEquals(expected,call("GET","/v2/evidence/versions/"+v+"/download",expert,null).statusCode());
            assertEquals(expected,call("POST","/v2/evidence/"+item+"/review",expert,Map.of("decision","approved")).statusCode());
            assertEquals(404,call("GET","/v2/evidence/"+item,login("h002.leader"),null).statusCode());
        }
        assertEquals(404,upload(surg,allowed).statusCode());
        assertEquals(200,upload(surg,otherDept).statusCode());
        String t=assign(office,"CARD","CL-3.1");
        assertEquals(404,call("PATCH","/v2/tasks/"+t,expert,Map.of("status","accepted")).statusCode());
        var req=HttpRequest.newBuilder(URI.create(base+"/v2/tasks/assign")).header("Authorization","Bearer "+surg).header("X-Demo-Role","quality").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        assertEquals(403,client.send(req,HttpResponse.BodyHandlers.ofString()).statusCode());
    }
    @Test void workbenchAndLegacyWorkspaceRespectFieldPermissions() throws Exception {
        for(String user:List.of("sys.admin","review.admin","leader.demo","surg.director","surg.staff","medical.reviewer","expert.demo","data.admin","audit.viewer","h002.leader")) {
            String token=login(user);var me=ok("GET","/auth/me",token,null);var home=ok("GET","/auth/workbench",token,null);
            if(!me.path("deptCode").asText().isEmpty())for(var t:home.path("tasks"))assertEquals(me.path("deptCode").asText(),t.path("deptCode").asText());
            Set<String> permissions=new HashSet<>();me.path("permissions").forEach(p->permissions.add(p.asText()));
            var snapshot=ok("GET","/workspace",token,null);
            for(var pair:Map.of("audit.read","auditLogs","batch.read","batches","report.read","scoreRuns","assessment.read","prerequisites","member.read","reconciliations").entrySet())if(!permissions.contains(pair.getKey()))assertTrue(snapshot.path(pair.getValue()).isEmpty(),user+":"+pair.getValue());
        }
    }
    @Test void expiredAndLockedSessionsAndProductionDemoRejection() throws Exception {
        String token=login("card.nurse");var jdbc=app.getBean(JdbcTemplate.class);
        jdbc.update("UPDATE auth_session SET expires_at=0 WHERE token_hash=?",cn.demo.review.auth.PasswordHash.digest(token));
        assertEquals(401,call("GET","/auth/me",token,null).statusCode());
        for(int n=0;n<5;n++)assertEquals(401,call("POST","/auth/login",null,Map.of("username","resp.nurse","password","incorrect-demo-password")).statusCode());
        assertEquals(401,call("POST","/auth/login",null,Map.of("username","resp.nurse","password",password)).statusCode());
        jdbc.update("UPDATE auth_account SET locked_until=0 WHERE username='resp.nurse'");assertFalse(login("resp.nurse").isBlank());
        String valid=login("leader.demo");
        try(var prod=new SpringApplicationBuilder(DemoApplication.class).run("--server.port=0","--review.profile=prod","--review.auth.legacy-demo-headers=true","--spring.datasource.url="+app.getEnvironment().getProperty("spring.datasource.url"),"--spring.main.banner-mode=off")) {
            String origin="http://127.0.0.1:"+prod.getEnvironment().getProperty("local.server.port")+"/api";
            var login=HttpRequest.newBuilder(URI.create(origin+"/auth/login")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("username","review.admin","password",password)))).build();
            assertEquals(401,client.send(login,HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(401,client.send(HttpRequest.newBuilder(URI.create(origin+"/v2/tasks")).header("Authorization","Bearer "+valid).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(403,client.send(HttpRequest.newBuilder(URI.create(origin+"/workspace")).header("X-Demo-Role","quality").GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
            for(String path:List.of("/auth/login","/auth/demo-accounts","/health"))assertEquals(403,client.send(HttpRequest.newBuilder(URI.create(origin+path)).header("X-Demo-Role","quality").GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode(),path);
        }
    }

    @Test void homeCountsAreNotTruncatedByAlertPreviewLimit() throws Exception {
        var jdbc=app.getBean(JdbcTemplate.class);
        for(int n=0;n<105;n++)jdbc.update("INSERT INTO v3_alert (id,hospital_id,metric_code,dept_code,status,created_at) VALUES (?,?,?,?,?,?)","auth-count-"+n,"H001","i01","GYNE","open","2026-11-03");
        var home=ok("GET","/auth/workbench",login("gyne.director"),null);
        assertEquals(100,home.path("alerts").size());assertEquals(105,home.path("stats").path("openAlerts").asInt());
        assertEquals(0,ok("GET","/auth/workbench",login("h002.leader"),null).path("stats").path("openAlerts").asInt());
    }

    @Test void scopedManualSubmissionPreservesHospitalValueAndDataAdminMembersWork() throws Exception {
        String office=login("review.admin"),staff=login("surg.staff"),head=login("surg.director");
        JsonNode before=null;for(var item:ok("GET","/workspace",office,null).path("indicators"))if(item.path("id").asText().equals("i01"))before=item;
        assertNotNull(before);
        assertEquals(403,call("POST","/manual-values",staff,Map.of("indicatorId","i01","deptCode","CARD","period","2026-09","value",0,"note","Synthetic own-department correction")).statusCode());
        var manual=ok("POST","/manual-values",staff,Map.of("indicatorId","i01","deptCode","SURG","period","2026-09","value",0,"note","Synthetic own-department correction"));
        assertEquals(403,call("POST","/manual-values/"+manual.path("id").asText()+"/review",head,Map.of("decision","approved","note","No hospital review permission")).statusCode());
        ok("POST","/manual-values/"+manual.path("id").asText()+"/review",office,Map.of("decision","approved","note","Synthetic review"));
        for(var item:ok("GET","/workspace",head,null).path("indicators"))if(item.path("id").asText().equals("i01")){assertEquals(0,item.path("value").asDouble());assertEquals("green",item.path("light").asText());}
        for(var item:ok("GET","/workspace",office,null).path("indicators"))if(item.path("id").asText().equals("i01"))assertEquals(before.path("value"),item.path("value"));
        assertEquals(200,call("GET","/indicators/i01/members",login("data.admin"),null).statusCode());
        assertEquals(403,call("GET","/indicators/i01/members",staff,null).statusCode());
    }
    @Test void dataAdministratorCanConfigureAndApplyBatchesButCannotReviewTasks() throws Exception {
        String data=login("data.admin");
        var mapping=ok("GET","/v3/source-mappings?sourceCode=CSV-IMPORT",data,null).path("items").get(0);
        ok("PUT","/v3/source-mappings",data,Map.of("sourceCode","CSV-IMPORT","fields",mapping.path("fields"),"codeMap",mapping.path("codeMap")));
        var batch=ok("POST","/v3/ingestion/batches",data,Map.of("sourceCode","CSV-IMPORT","period","2026-10","rows",List.of(Map.of("code","i01","dept","SURG","num",1,"den",1000,"unit","%"))));
        ok("POST","/v3/ingestion/batches/"+batch.path("batch").path("id").asText()+"/apply",data,Map.of());
        assertEquals(403,call("POST","/v2/score-runs",data,Map.of()).statusCode());
    }

}
