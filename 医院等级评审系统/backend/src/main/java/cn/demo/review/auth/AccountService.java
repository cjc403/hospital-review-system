package cn.demo.review.auth;

import cn.demo.review.v2.V2Exception;
import cn.demo.review.v2.platform.*;
import cn.demo.review.v2.security.AuthContext;
import com.fasterxml.jackson.databind.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service
public class AccountService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ReviewProfile profile;
    private final AuditService audit;
    private final String dummyHash=PasswordHash.hash(UUID.randomUUID().toString());
    public AccountService(JdbcTemplate jdbc,ObjectMapper json,ReviewProfile profile,AuditService audit){this.jdbc=jdbc;this.json=json;this.profile=profile;this.audit=audit;}
    private String str(Map<String,Object> row,String key){Object v=row.get(key);return v==null?null:String.valueOf(v);}
    private long num(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private Map<String,Object> account(String id,String hospital){
        var rows=jdbc.queryForList("SELECT * FROM auth_account WHERE id=? AND hospital_id=?",id,hospital);
        if(rows.isEmpty())throw V2Exception.notFound("账号不存在或不在本院");return rows.get(0);
    }
    private Set<String> clauses(Map<String,Object> a){try{return new LinkedHashSet<>(json.readValue(str(a,"clause_codes_json"),json.getTypeFactory().constructCollectionType(List.class,String.class)));}catch(Exception e){throw new IllegalStateException("账号条款范围无效",e);}}
    private AuthContext context(Map<String,Object> a){return new AuthContext(str(a,"id"),str(a,"hospital_id"),str(a,"dept_code"),Set.of(str(a,"role_code")),profile.isProd()?"prod":"demo",clauses(a),true);}
    public Map<String,Object> view(Map<String,Object> a){
        Map<String,Object> out=new LinkedHashMap<>(context(a).describe());
        out.put("id",str(a,"id"));out.put("username",str(a,"username"));out.put("displayName",str(a,"display_name"));out.put("status",str(a,"status"));
        out.put("hospitalName",jdbc.queryForObject("SELECT name FROM hospital WHERE id=?",String.class,str(a,"hospital_id")));
        var names=jdbc.query("SELECT name FROM department WHERE hospital_id=? AND code=?",(rs,i)->rs.getString(1),str(a,"hospital_id"),str(a,"dept_code"));
        out.put("deptName",names.isEmpty()?"全院":names.get(0));return out;
    }
    @Transactional(noRollbackFor=V2Exception.class)
    public Map<String,Object> login(JsonNode body,String requestId){
        String username=text(body,"username",80).toLowerCase(Locale.ROOT),password=secret(body,"password");
        var rows=jdbc.queryForList("SELECT * FROM auth_account WHERE username=? FOR UPDATE",username);
        long now=System.currentTimeMillis();
        if(rows.isEmpty()){PasswordHash.matches(password,dummyHash);throw V2Exception.unauthorized("账号或密码错误，或账号暂不可用");}
        var a=rows.get(0);
        if(num(a,"locked_until")>now||!"active".equals(str(a,"status"))||(profile.isProd()&&num(a,"is_demo")==1))throw V2Exception.unauthorized("账号或密码错误，或账号暂不可用");
        if(!PasswordHash.matches(password,str(a,"password_hash"))){
            int failures=(int)num(a,"failed_attempts")+1;
            jdbc.update("UPDATE auth_account SET failed_attempts=?,locked_until=? WHERE id=?",failures>=5?0:failures,failures>=5?now+60000:0,str(a,"id"));
            audit.record(context(a),requestId,"登录失败","account",str(a,"id"),"凭据校验失败");
            throw V2Exception.unauthorized("账号或密码错误，或账号暂不可用");
        }
        jdbc.update("UPDATE auth_account SET failed_attempts=0,locked_until=0 WHERE id=?",str(a,"id"));
        String token=PasswordHash.token();long expires=now+8*60*60*1000L;
        jdbc.update("INSERT INTO auth_session (token_hash,account_id,session_version,expires_at,created_at,revoked) VALUES (?,?,?,?,?,0)",PasswordHash.digest(token),str(a,"id"),num(a,"session_version"),expires,now);
        audit.record(context(a),requestId,"账号登录","account",str(a,"id"),"本地会话登录");
        return Map.of("token",token,"expiresAt",Instant.ofEpochMilli(expires).toString(),"user",view(a));
    }
    public AuthContext authenticate(String token){
        if(token==null||token.length()<32||token.length()>128)throw V2Exception.unauthorized("登录已失效，请重新登录");
        var rows=jdbc.queryForList("SELECT a.* FROM auth_session s JOIN auth_account a ON a.id=s.account_id WHERE s.token_hash=? AND s.revoked=0 AND s.expires_at>? AND s.session_version=a.session_version AND a.status='active'",PasswordHash.digest(token),System.currentTimeMillis());
        if(rows.isEmpty()||(profile.isProd()&&num(rows.get(0),"is_demo")==1))throw V2Exception.unauthorized("登录已失效，请重新登录");
        return context(rows.get(0));
    }
    @Transactional public void logout(AuthContext ctx,String token,String requestId){jdbc.update("UPDATE auth_session SET revoked=1 WHERE token_hash=? AND account_id=?",PasswordHash.digest(token),ctx.userId());audit.record(ctx,requestId,"退出登录","account",ctx.userId(),"当前会话已撤销");}
    public Map<String,Object> me(AuthContext ctx){return view(account(ctx.userId(),ctx.hospitalId()));}
    public List<Map<String,Object>> demoAccounts(){if(profile.isProd())return List.of();return jdbc.queryForList("SELECT * FROM auth_account WHERE is_demo=1 AND status='active' ORDER BY hospital_id,role_code,username").stream().map(a->{var v=view(a);v.remove("permissions");v.remove("clauseCodes");v.remove("roles");return v;}).toList();}
    public List<Map<String,Object>> list(AuthContext ctx){ctx.requirePermission("account.manage");return jdbc.queryForList("SELECT * FROM auth_account WHERE hospital_id=? ORDER BY role_code,username",ctx.hospitalId()).stream().map(this::view).toList();}
    @Transactional public Map<String,Object> create(AuthContext ctx,JsonNode body,String requestId){
        ctx.requirePermission("account.manage");String username=text(body,"username",64).toLowerCase(Locale.ROOT);
        if(!username.matches("[a-z][a-z0-9._-]{2,63}"))throw V2Exception.badRequest("账号须为3至64位小写字母、数字、点、下划线或横线");
        String name=text(body,"displayName",100),role=text(body,"roleCode",40),dept=body.path("deptCode").asText("");
        Set<String> clauses=parseClauses(body.path("clauseCodes"));validateScope(ctx.hospitalId(),role,dept,clauses);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM auth_account WHERE username=?",Integer.class,username)>0)throw V2Exception.conflict("账号已存在");
        String password=password(body,"password");String id="usr-"+UUID.randomUUID();
        jdbc.update("INSERT INTO auth_account (id,username,display_name,hospital_id,dept_code,role_code,clause_codes_json,password_hash,status,session_version,failed_attempts,locked_until,is_demo,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,1,0,0,?,?,?)",id,username,name,ctx.hospitalId(),dept.isBlank()?null:dept,role,encode(clauses),PasswordHash.hash(password),"active",profile.isDemo()?1:0,now(),now());
        audit.record(ctx,requestId,"创建账号","account",id,"岗位="+role);return view(account(id,ctx.hospitalId()));
    }
    @Transactional public Map<String,Object> update(AuthContext ctx,String id,JsonNode body,String requestId){
        ctx.requirePermission("account.manage");
        // 锁定本院管理员集合，避免并发停用最后一个管理员。
        jdbc.queryForList("SELECT id FROM auth_account WHERE hospital_id=? AND role_code='system_admin' ORDER BY id FOR UPDATE",ctx.hospitalId());
        var a=account(id,ctx.hospitalId());String role=body.has("roleCode")?text(body,"roleCode",40):str(a,"role_code");
        String dept=body.has("deptCode")?body.path("deptCode").asText(""):Objects.toString(a.get("dept_code"),"");
        String status=body.has("status")?text(body,"status",20):str(a,"status");if(!Set.of("active","disabled").contains(status))throw V2Exception.badRequest("账号状态无效");
        Set<String> clauses=body.has("clauseCodes")?parseClauses(body.get("clauseCodes")):clauses(a);validateScope(ctx.hospitalId(),role,dept,clauses);
        boolean changesAuthority=!role.equals(str(a,"role_code"))||!dept.equals(Objects.toString(a.get("dept_code"),""))||!clauses.equals(clauses(a))||!status.equals(str(a,"status"));
        if(id.equals(ctx.userId())&&changesAuthority)throw V2Exception.conflict("不能修改自己的角色、范围或启停状态");
        if("system_admin".equals(str(a,"role_code"))&&"active".equals(str(a,"status"))&&(!role.equals("system_admin")||!status.equals("active"))&&jdbc.queryForObject("SELECT COUNT(*) FROM auth_account WHERE hospital_id=? AND role_code='system_admin' AND status='active'",Integer.class,ctx.hospitalId())<=1)throw V2Exception.conflict("必须保留至少一个活跃系统管理员");
        String name=body.has("displayName")?text(body,"displayName",100):str(a,"display_name");
        jdbc.update("UPDATE auth_account SET display_name=?,role_code=?,dept_code=?,clause_codes_json=?,status=?,session_version=session_version+?,updated_at=? WHERE id=? AND hospital_id=?",name,role,dept.isBlank()?null:dept,encode(clauses),status,changesAuthority?1:0,now(),id,ctx.hospitalId());
        audit.record(ctx,requestId,"修改账号","account",id,"岗位="+role+" 状态="+status);return view(account(id,ctx.hospitalId()));
    }
    @Transactional public void reset(AuthContext ctx,String id,JsonNode body,String requestId){ctx.requirePermission("account.manage");account(id,ctx.hospitalId());setPassword(id,ctx.hospitalId(),password(body,"password"));audit.record(ctx,requestId,"重置账号密码","account",id,"已撤销旧会话");}
    @Transactional public void changePassword(AuthContext ctx,JsonNode body,String requestId){var a=account(ctx.userId(),ctx.hospitalId());if(!PasswordHash.matches(secret(body,"currentPassword"),str(a,"password_hash")))throw V2Exception.badRequest("当前密码不正确");setPassword(ctx.userId(),ctx.hospitalId(),password(body,"newPassword"));audit.record(ctx,requestId,"修改本人密码","account",ctx.userId(),"已撤销旧会话");}
    private void setPassword(String id,String hospital,String password){jdbc.update("UPDATE auth_account SET password_hash=?,session_version=session_version+1,failed_attempts=0,locked_until=0,updated_at=? WHERE id=? AND hospital_id=?",PasswordHash.hash(password),now(),id,hospital);}
    private String password(JsonNode b,String field){String p=b!=null&&b.has(field)?secret(b,field):profile.isDemo()&&field.equals("password")?DemoAccountSeeder.INITIAL_PASSWORD:null;if(p==null||p.length()<10)throw V2Exception.badRequest("密码须为10至256位");return p;}
    private String secret(JsonNode b,String field){if(b==null||!b.path(field).isTextual()||b.path(field).asText().isEmpty()||b.path(field).asText().length()>256)throw V2Exception.badRequest("密码字段无效");return b.path(field).asText();}
    private String text(JsonNode b,String field,int max){if(b==null||!b.path(field).isTextual()||b.path(field).asText().isBlank()||b.path(field).asText().length()>max)throw V2Exception.badRequest(field+"不能为空且须在长度限制内");return b.path(field).asText().trim();}
    private Set<String> parseClauses(JsonNode node){Set<String> out=new LinkedHashSet<>();if(node.isMissingNode()||node.isNull())return out;if(!node.isArray()||node.size()>100)throw V2Exception.badRequest("条款范围须为数组");for(JsonNode n:node){if(!n.isTextual()||n.asText().length()>60)throw V2Exception.badRequest("条款编码无效");out.add(n.asText());}return out;}
    private void validateScope(String hospital,String role,String dept,Set<String> clauses){
        if(RolePermissions.role(role)==null)throw V2Exception.badRequest("角色不存在");
        if(RolePermissions.departmentRole(role)&&dept.isBlank())throw V2Exception.badRequest("该角色必须指定科室");
        if(!RolePermissions.departmentRole(role)&&!dept.isBlank())throw V2Exception.badRequest("院级角色不能附带科室范围");
        if(!dept.isBlank()&&jdbc.queryForObject("SELECT COUNT(*) FROM department WHERE hospital_id=? AND code=?",Integer.class,hospital,dept)==0)throw V2Exception.badRequest("科室不存在于本院");
        if(role.equals("expert")&&clauses.isEmpty())throw V2Exception.badRequest("专家必须指定授权条款");
        if(!role.equals("expert")&&!clauses.isEmpty())throw V2Exception.badRequest("仅专家使用条款授权范围");
        for(String c:clauses)if(jdbc.queryForObject("SELECT COUNT(*) FROM standard_clause WHERE code=?",Integer.class,c)==0)throw V2Exception.badRequest("条款不存在");
    }
    private String encode(Set<String> values){try{return json.writeValueAsString(values);}catch(Exception e){throw new IllegalStateException(e);}}
    private String now(){return OffsetDateTime.now(ZoneOffset.UTC).toString();}
}
