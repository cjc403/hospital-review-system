package cn.demo.review.v2.security;

import org.springframework.stereotype.Component;

/**
 * 真实身份接入点（OIDC / LDAP / AD）。
 * 当前本地没有身份提供方，实现一律返回 null，因此 prod profile 下所有请求都是 401。
 * 真实接入还须对接新账号/权限模型和 v1 身份映射；不可只替换本类即认为接入完成。
 */
public interface TokenAuthenticator {
    AuthContext authenticate(String token);

    @Component
    class RejectingTokenAuthenticator implements TokenAuthenticator {
        @Override
        public AuthContext authenticate(String token) {
            return null;
        }
    }
}
