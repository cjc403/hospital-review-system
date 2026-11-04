package cn.demo.review.auth;

import cn.demo.review.v2.platform.*;
import cn.demo.review.v2.security.*;
import cn.demo.review.v2.task.TaskService;
import cn.demo.review.v3.alert.AlertService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/auth")
public class AccountController {
    private final AccountService accounts;private final TaskService tasks;private final AlertService alerts;private final AuditService audit;
    public AccountController(AccountService accounts,TaskService tasks,AlertService alerts,AuditService audit){this.accounts=accounts;this.tasks=tasks;this.alerts=alerts;this.audit=audit;}
    @PostMapping("/login") public Object login(@RequestBody JsonNode body,HttpServletRequest r){return accounts.login(body,RequestIdFilter.of(r));}
    @GetMapping("/demo-accounts") public Object demo(){return Map.of("items",accounts.demoAccounts());}
    @GetMapping("/me") public Object me(HttpServletRequest r){return accounts.me(AuthFilter.current(r));}
    @PostMapping("/logout") public Object logout(HttpServletRequest r){accounts.logout(AuthFilter.current(r),r.getHeader("Authorization").substring(7),RequestIdFilter.of(r));return Map.of("ok",true);}
    @PostMapping("/password") public Object password(@RequestBody JsonNode b,HttpServletRequest r){accounts.changePassword(AuthFilter.current(r),b,RequestIdFilter.of(r));return Map.of("ok",true);}
    @GetMapping("/roles") public Object roles(){return Map.of("items",RolePermissions.describe());}
    @GetMapping("/accounts") public Object list(HttpServletRequest r){return Map.of("items",accounts.list(AuthFilter.current(r)));}
    @PostMapping("/accounts") public Object create(@RequestBody JsonNode b,HttpServletRequest r){return accounts.create(AuthFilter.current(r),b,RequestIdFilter.of(r));}
    @PatchMapping("/accounts/{id}") public Object update(@PathVariable String id,@RequestBody JsonNode b,HttpServletRequest r){return accounts.update(AuthFilter.current(r),id,b,RequestIdFilter.of(r));}
    @PostMapping("/accounts/{id}/reset-password") public Object reset(@PathVariable String id,@RequestBody(required=false) JsonNode b,HttpServletRequest r){accounts.reset(AuthFilter.current(r),id,b,RequestIdFilter.of(r));return Map.of("ok",true);}
    @GetMapping("/audit") public Object audit(HttpServletRequest r,@RequestParam(defaultValue="100") int limit){var c=AuthFilter.current(r);c.requirePermission("audit.read");return Map.of("items",audit.list(c.hospitalId(),Math.max(1,limit)));}
    @GetMapping("/workbench") public Object workbench(HttpServletRequest r){
        var c=AuthFilter.current(r);
        var ts=c.can("task.read")?tasks.list(c,null,null,false).get("items"):List.of();
        var as=c.can("alert.read")?alerts.listAlerts(c,null,null,100).get("items"):List.of();
        var ims=c.can("improvement.read")?alerts.listImprovements(c,null).get("items"):List.of();
        var stats=new LinkedHashMap<String,Object>();stats.put("tasks",((List<?>)ts).size());stats.put("alerts",((List<?>)as).size());stats.put("improvements",((List<?>)ims).size());
        stats.put("openTasks",((List<Map<String,Object>>)ts).stream().filter(t->!Set.of("accepted","cancelled").contains(t.get("status"))).count());
        stats.put("openAlerts",c.can("alert.read")?alerts.openAlertCount(c):0);
        stats.put("openImprovements",c.can("improvement.read")?alerts.openImprovementCount(c):0);
        if(c.can("account.manage"))stats.put("accounts",accounts.list(c).size());
        return Map.of("tasks",ts,"alerts",as,"improvements",ims,"stats",stats);
    }
}
