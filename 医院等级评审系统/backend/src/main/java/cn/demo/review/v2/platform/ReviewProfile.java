package cn.demo.review.v2.platform;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;

/**
 * 运行 profile 边界。demo 允许演示角色头；prod 一律拒绝演示身份，必须走真实令牌。
 * 启用 Spring 的 prod 运行 profile 时，必须同时把 review.profile 设为 prod，否则启动失败——
 * 避免"以为在生产、实际还开着演示身份"的静默降级。
 */
@Component
public class ReviewProfile {
    public static final Set<String> SUPPORTED = Set.of("demo", "prod");

    private final String name;

    public ReviewProfile(@Value("${review.profile:demo}") String name, Environment environment) {
        this.name = resolve(name, environment.getActiveProfiles());
    }

    public static String resolve(String configured, String... activeProfiles) {
        String normalized = configured == null || configured.isBlank() ? "demo" : configured.trim().toLowerCase();
        if (!SUPPORTED.contains(normalized)) {
            throw new IllegalStateException("review.profile 只支持 demo 或 prod，当前为 " + configured);
        }
        boolean springProd = activeProfiles != null
                && Arrays.stream(activeProfiles).anyMatch(p -> "prod".equalsIgnoreCase(p));
        if (springProd && !"prod".equals(normalized)) {
            throw new IllegalStateException("启用 prod 运行 profile 时必须设置 review.profile=prod");
        }
        return normalized;
    }

    public String name() { return name; }
    public boolean isDemo() { return "demo".equals(name); }
    public boolean isProd() { return "prod".equals(name); }
}
