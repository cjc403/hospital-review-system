package cn.demo.review.auth;

import java.util.*;

/** 服务端权限唯一目录，UI 直接使用返回值。组织范围独立于岗位权限。 */
public final class RolePermissions {
    public record Role(String code,String name,Set<String> permissions){}
    private static final Map<String,Role> ROLES=new LinkedHashMap<>();
    static {
        add("system_admin","系统管理员","workspace.read standard.read account.manage audit.read");
        add("review_office","评审办管理员","workspace.read standard.read standard.write task.read task.assign task.progress task.review evidence.read evidence.upload evidence.review metric.read member.read batch.read batch.write mapping.write alert.read alert.ack alert.manage improvement.read improvement.write improvement.review report.read report.write assessment.read assessment.write audit.read manual.submit legacy.write");
        add("leader","院级领导","workspace.read standard.read task.read evidence.read metric.read alert.read improvement.read report.read assessment.read");
        add("dept_head","科室负责人","workspace.read standard.read task.read task.progress task.review evidence.read evidence.upload evidence.review metric.read member.read alert.read alert.ack improvement.read improvement.write manual.submit");
        add("dept_liaison","科室联络员","workspace.read standard.read task.read task.progress evidence.read evidence.upload metric.read alert.read improvement.read manual.submit");
        add("quality_reviewer","职能质控人员","workspace.read standard.read task.read task.review evidence.read evidence.upload evidence.review metric.read alert.read alert.ack improvement.read improvement.review");
        add("expert","内审／预评专家","workspace.read standard.read task.read task.review evidence.read evidence.review");
        add("indicator_admin","数据管理员","workspace.read standard.read metric.read member.read batch.read batch.write mapping.write audit.read");
        add("auditor","审计只读人员","workspace.read standard.read metric.read task.read report.read assessment.read audit.read");
    }
    private static void add(String code,String name,String permissions){ROLES.put(code,new Role(code,name,Collections.unmodifiableSet(new LinkedHashSet<>(List.of(permissions.split(" "))))));}
    public static Role role(String code){return ROLES.get(code);}
    public static Set<String> permissions(Set<String> roles){
        Set<String> out=new LinkedHashSet<>();
        for(String code:roles){
            if(ROLES.containsKey(code))out.addAll(ROLES.get(code).permissions());
            // 仅用于已存在的内部服务/隔离旧契约测试上下文。
            else if(code.equals("observer"))out.addAll(ROLES.get("leader").permissions());
            else if(code.equals("evidence_admin"))out.addAll(Set.of("workspace.read","standard.read","evidence.read","evidence.upload","evidence.review","report.read"));
        }
        return Set.copyOf(out);
    }
    public static List<Map<String,Object>> describe(){return ROLES.values().stream().map(r->Map.<String,Object>of("code",r.code(),"name",r.name(),"permissions",r.permissions())).toList();}
    public static boolean departmentRole(String role){return Set.of("dept_head","dept_liaison","quality_reviewer","expert").contains(role);}
    public static String legacy(String role){if(role.equals("review_office"))return "quality";if(departmentRole(role))return "department";if(role.equals("leader"))return "leader";return "information";}
    private RolePermissions(){}
}
