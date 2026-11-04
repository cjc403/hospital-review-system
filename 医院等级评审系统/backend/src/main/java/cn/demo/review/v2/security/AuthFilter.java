package cn.demo.review.v2.security;

import cn.demo.review.v2.V2Exception;
import cn.demo.review.v2.platform.RequestIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全部 /api 业务的认证入口。认证失败在过滤器内直接返回契约错误体，不会落到业务控制器。
 * 未认证或越权时业务代码根本不执行。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthFilter extends OncePerRequestFilter {
    public static final String ATTRIBUTE = "review.authContext";

    private final AuthService authService;
    private final ObjectMapper json;
    private final cn.demo.review.v2.platform.ReviewProfile profile;

    public AuthFilter(AuthService authService, ObjectMapper json,cn.demo.review.v2.platform.ReviewProfile profile) {
        this.authService = authService;
        this.json = json;this.profile=profile;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) return true;
        if(path.startsWith("/api/")&&profile.isProd()&&request.getHeader(AuthService.DEMO_ROLE_HEADER)!=null)return false;
        // v1/v2/v3 共用登录上下文；健康、登录和合成账号目录允许匿名。
        return !path.startsWith("/api/") || path.equals("/api/health") || path.equals("/api/auth/login") || path.equals("/api/auth/demo-accounts");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            AuthContext ctx = authService.resolve(request);
            request.setAttribute(ATTRIBUTE, ctx);
            cn.demo.review.auth.EndpointPermissions.authorize(ctx,request.getMethod(),request.getRequestURI());
            cn.demo.review.auth.RequestIdentity.set(ctx);
            jakarta.servlet.http.HttpServletRequest safeRequest = ctx.accountSession() ? new jakarta.servlet.http.HttpServletRequestWrapper(request) {
                @Override public String getHeader(String name) {return "X-Demo-Role".equalsIgnoreCase(name) ? ctx.legacyRole() : super.getHeader(name);}
                @Override public java.util.Enumeration<String> getHeaders(String name) {return "X-Demo-Role".equalsIgnoreCase(name) ? java.util.Collections.enumeration(java.util.List.of(ctx.legacyRole())) : super.getHeaders(name);}
            } : request;
            chain.doFilter(safeRequest, response);
        } catch (V2Exception e) {
            response.setStatus(e.status);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", e.code);
            body.put("message", e.getMessage());
            body.put("requestId", RequestIdFilter.of(request));
            body.put("details", e.details);
            response.getWriter().write(json.writeValueAsString(body));
        } finally {cn.demo.review.auth.RequestIdentity.clear();}
    }

    public static AuthContext current(HttpServletRequest request) {
        Object ctx = request.getAttribute(ATTRIBUTE);
        if (ctx instanceof AuthContext auth) return auth;
        throw V2Exception.unauthorized("未认证请求");
    }
}
