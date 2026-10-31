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
 * /api/v2 的认证入口。认证失败在过滤器内直接返回契约错误体，不会落到业务控制器。
 * 未认证或越权时业务代码根本不执行。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthFilter extends OncePerRequestFilter {
    public static final String ATTRIBUTE = "review.authContext";

    private final AuthService authService;
    private final ObjectMapper json;

    public AuthFilter(AuthService authService, ObjectMapper json) {
        this.authService = authService;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) return true;
        // v2 与 v3 共用同一套认证与租户上下文；v1 保持原有演示契约不动。
        return !path.startsWith("/api/v2") && !path.startsWith("/api/v3");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            AuthContext ctx = authService.resolve(request);
            request.setAttribute(ATTRIBUTE, ctx);
            chain.doFilter(request, response);
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
        }
    }

    public static AuthContext current(HttpServletRequest request) {
        Object ctx = request.getAttribute(ATTRIBUTE);
        if (ctx instanceof AuthContext auth) return auth;
        throw V2Exception.unauthorized("未认证请求");
    }
}
