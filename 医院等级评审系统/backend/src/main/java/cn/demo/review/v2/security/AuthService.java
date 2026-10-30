package cn.demo.review.v2.security;

import cn.demo.review.v2.V2Exception;
import cn.demo.review.v2.platform.ReviewProfile;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 身份解析。demo 走演示角色头（仅本机演示），prod 只接受真实令牌并拒绝演示头。
 */
@Service
public class AuthService {
    public static final String DEMO_ROLE_HEADER = "X-Demo-Role";
    public static final String DEMO_HOSPITAL = "H001";

    private final ReviewProfile profile;
    private final TokenAuthenticator authenticator;

    public AuthService(ReviewProfile profile, TokenAuthenticator authenticator) {
        this.profile = profile;
        this.authenticator = authenticator;
    }

    public AuthContext resolve(HttpServletRequest request) {
        if (profile.isProd()) return resolveProd(request);
        return resolveDemo(request);
    }

    private AuthContext resolveProd(HttpServletRequest request) {
        if (request.getHeader(DEMO_ROLE_HEADER) != null) {
            throw V2Exception.profile("生产模式不接受 X-Demo-Role 演示身份");
        }
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw V2Exception.unauthorized("缺少访问令牌");
        }
        String token = authorization.substring("Bearer ".length()).trim();
        if (token.isEmpty()) throw V2Exception.unauthorized("缺少访问令牌");
        AuthContext ctx = authenticator.authenticate(token);
        if (ctx == null) throw V2Exception.unauthorized("令牌无效或已过期");
        return ctx;
    }

    private AuthContext resolveDemo(HttpServletRequest request) {
        String role = request.getHeader(DEMO_ROLE_HEADER);
        if (role == null || role.isBlank()) {
            throw V2Exception.unauthorized("演示模式需要 X-Demo-Role 请求头");
        }
        String normalized = role.trim().toLowerCase();
        return switch (normalized) {
            case "quality" -> new AuthContext("demo-review-office", DEMO_HOSPITAL, null,
                    Set.of("review_office"), "demo");
            case "leader" -> new AuthContext("demo-leader", DEMO_HOSPITAL, null,
                    Set.of("leader"), "demo");
            case "department" -> new AuthContext("demo-dept-head", DEMO_HOSPITAL, "CARD",
                    Set.of("dept_head"), "demo");
            case "information" -> new AuthContext("demo-observer", DEMO_HOSPITAL, null,
                    Set.of("observer"), "demo");
            default -> throw V2Exception.unauthorized("未知演示角色");
        };
    }
}
