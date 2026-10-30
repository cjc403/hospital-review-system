package cn.demo.review.v2.security;

import cn.demo.review.v2.V2Exception;

import java.util.List;
import java.util.Set;

/** 认证后的服务端上下文。医院是租户边界，科室是数据范围边界，角色只由服务端判定。 */
public final class AuthContext {
    private final String userId;
    private final String hospitalId;
    private final String deptCode;
    private final Set<String> roles;
    private final String profile;

    public AuthContext(String userId, String hospitalId, String deptCode, Set<String> roles, String profile) {
        this.userId = userId;
        this.hospitalId = hospitalId;
        this.deptCode = deptCode == null || deptCode.isBlank() ? null : deptCode;
        this.roles = Set.copyOf(roles);
        this.profile = profile;
    }

    public String userId() { return userId; }
    public String hospitalId() { return hospitalId; }
    public String deptCode() { return deptCode; }
    public Set<String> roles() { return roles; }
    public String profile() { return profile; }
    public boolean demo() { return "demo".equals(profile); }
    public boolean has(String role) { return roles.contains(role); }
    public boolean deptScoped() { return deptCode != null; }

    public AuthContext require(String... allowed) {
        for (String role : allowed) if (roles.contains(role)) return this;
        throw V2Exception.forbidden("当前角色无权执行该操作");
    }

    /** 科室范围校验：有科室范围的角色只能作用于本科室。 */
    public void requireDept(String deptCode) {
        if (this.deptCode != null && !this.deptCode.equals(deptCode)) {
            throw V2Exception.forbidden("只能操作本科室数据");
        }
    }

    public boolean canManageDept(String deptCode) {
        return this.deptCode == null || this.deptCode.equals(deptCode);
    }

    public java.util.Map<String, Object> describe() {
        java.util.Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("userId", userId);
        view.put("hospitalId", hospitalId);
        view.put("deptCode", deptCode == null ? "" : deptCode);
        view.put("roles", List.copyOf(roles));
        view.put("profile", profile);
        view.put("demo", demo());
        return view;
    }
}
