package cn.demo.review.auth;

import cn.demo.review.v2.V2Exception;
import cn.demo.review.v2.security.AuthContext;

/** 默认拒绝。下载与旧 v1 接口和页面请求使用同一认证边界。 */
public final class EndpointPermissions {
    public static void authorize(AuthContext ctx,String method,String path){
        if(!ctx.accountSession())return; // 仅显式旧契约隔离模式/内部上下文。
        if(!ctx.hospitalId().equals("H001") && !path.startsWith("/api/auth/") && !path.startsWith("/api/v2/") && !path.startsWith("/api/v3/") && !path.equals("/api/workspace"))throw V2Exception.notFound("旧版记录不在本院范围");
        if(path.startsWith("/api/v2/tasks/") && method.equals("PATCH")){
            if(!ctx.can("task.progress")&&!ctx.can("task.review"))throw V2Exception.forbidden("无权变更任务");
            return;
        }
        String permission=permission(method,path);
        if(permission==null)throw V2Exception.forbidden("当前账号无权访问此接口");
        if(!permission.isEmpty())ctx.requirePermission(permission);
    }
    private static String permission(String method,String path){
        boolean read=method.equals("GET")||method.equals("HEAD");
        if(path.startsWith("/api/auth/")){
            if(path.startsWith("/api/auth/accounts"))return "account.manage";
            if(path.equals("/api/auth/audit"))return "audit.read";
            return "";
        }
        if(path.equals("/api/workspace"))return "workspace.read";
        if(path.startsWith("/api/indicators/"))return "member.read";
        if(path.equals("/api/manual-values")&&method.equals("POST"))return "manual.submit";
        if(path.startsWith("/api/evidence/"))return "legacy.write";
        if(path.startsWith("/api/reports/"))return "report.read";
        if(!path.startsWith("/api/v2/")&&!path.startsWith("/api/v3/"))return "legacy.write";
        String p=path.substring(7);
        if(p.equals("/me")||p.equals("/departments"))return "workspace.read";
        if(p.startsWith("/standards"))return read?"standard.read":"standard.write";
        if(p.startsWith("/tasks"))return read?"task.read":p.endsWith("/assign")?"task.assign":null;
        if(p.startsWith("/evidence"))return read?"evidence.read":p.endsWith("/review")?"evidence.review":"evidence.upload";
        if(p.startsWith("/prerequisites")||p.startsWith("/audit-samples"))return read?"assessment.read":"assessment.write";
        if(p.startsWith("/score-runs"))return read?"report.read":"report.write";
        if(p.startsWith("/audit-events"))return "audit.read";
        if(p.startsWith("/integration/")||p.startsWith("/batches")||p.startsWith("/ingestion/batches"))return read?"batch.read":"batch.write";
        if(p.startsWith("/data-sources"))return "batch.read";
        if(p.startsWith("/source-mappings"))return read?"batch.read":"mapping.write";
        if(p.startsWith("/metric-definitions"))return read?"standard.read":"standard.write";
        if(p.startsWith("/metric-results"))return p.endsWith("/drilldown")?"metric.read":"metric.read";
        if(p.startsWith("/lineage/"))return "metric.read";
        if(p.startsWith("/alerts"))return read?"alert.read":p.endsWith("/ack")?"alert.ack":"alert.manage";
        if(p.startsWith("/improvements"))return read?"improvement.read":p.endsWith("/review")?"improvement.review":"improvement.write";
        if(p.startsWith("/reports"))return read?"report.read":"report.write";
        return null;
    }
    private EndpointPermissions(){}
}
