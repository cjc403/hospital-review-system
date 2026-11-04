package cn.demo.review.auth;
import cn.demo.review.v2.security.AuthContext;
/** 旧 v1 的兼容适配上下文，finally 清理，不能信任浏览器角色头。 */
public final class RequestIdentity {
    private static final ThreadLocal<AuthContext> CURRENT=new ThreadLocal<>();
    public static void set(AuthContext context){CURRENT.set(context);}
    public static AuthContext get(){return CURRENT.get();}
    public static void clear(){CURRENT.remove();}
    private RequestIdentity(){}
}
