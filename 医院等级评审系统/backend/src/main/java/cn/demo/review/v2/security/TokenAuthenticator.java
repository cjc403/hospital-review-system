package cn.demo.review.v2.security;

import org.springframework.stereotype.Component;

/**
 * 真实身份接入点（OIDC / LDAP / AD）。
 * 当前本地没有身份提供方，实现一律返回 null，因此 prod profile 下所有请求都是 401。
 * 接入真实身份时只替换本实现，不改业务代码。
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
