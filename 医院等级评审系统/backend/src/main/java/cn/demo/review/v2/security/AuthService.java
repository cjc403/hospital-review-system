package cn.demo.review.v2.security;

import cn.demo.review.v2.V2Exception;
import cn.demo.review.v2.platform.ReviewProfile;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 身份解析。默认使用本地可撤销账号会话；旧演示头仅限显式隔离模式。
 * 真实统一身份适配尚未接入此账号体系，见 docs/auth-api-contract.md。
 */
@Service
public class AuthService {
    public static final String DEMO_ROLE_HEADER = "X-Demo-Role";
    public static final String DEMO_HOSPITAL = "H001";

    private final ReviewProfile profile;
    private final TokenAuthenticator authenticator;
    private final cn.demo.review.auth.AccountService accounts;
    private final boolean legacyHeaders;

    public AuthService(ReviewProfile profile, TokenAuthenticator authenticator,cn.demo.review.auth.AccountService accounts,
                       @org.springframework.beans.factory.annotation.Value("${review.auth.legacy-demo-headers:false}") boolean legacyHeaders) {
        this.profile = profile;
        this.authenticator = authenticator;
        this.accounts=accounts;this.legacyHeaders=legacyHeaders;
    }

    public AuthContext resolve(HttpServletRequest request) {
        if(profile.isProd()&&request.getHeader(DEMO_ROLE_HEADER)!=null)throw V2Exception.profile("生产模式不接受演示角色头");
        String bearer=request.getHeader("Authorization");
        if(bearer!=null&&bearer.startsWith("Bearer "))return accounts.authenticate(bearer.substring(7));
        if(profile.isProd())return resolveProd(request);
        if(legacyHeaders)return resolveDemo(request);
        throw V2Exception.unauthorized("请先登录");
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
            default -> throw request.getRequestURI().startsWith("/api/v") ? V2Exception.unauthorized("未知演示角色") : V2Exception.forbidden("未知演示角色");
        };
    }
}
