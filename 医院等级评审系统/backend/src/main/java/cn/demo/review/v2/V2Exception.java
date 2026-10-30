package cn.demo.review.v2;

import java.util.List;
import java.util.Map;

/** v2 统一业务异常：带错误码与可选明细，由 V2Errors 转成契约中的错误体。 */
public class V2Exception extends RuntimeException {
    public final int status;
    public final String code;
    public final List<Map<String, String>> details;

    public V2Exception(int status, String code, String message) { this(status, code, message, List.of()); }

    public V2Exception(int status, String code, String message, List<Map<String, String>> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public static V2Exception badRequest(String message) { return new V2Exception(400, "validation_failed", message); }
    public static V2Exception unauthorized(String message) { return new V2Exception(401, "unauthenticated", message); }
    public static V2Exception forbidden(String message) { return new V2Exception(403, "forbidden", message); }
    public static V2Exception notFound(String message) { return new V2Exception(404, "not_found", message); }
    public static V2Exception conflict(String message) { return new V2Exception(409, "conflict", message); }
    public static V2Exception immutable(String message) { return new V2Exception(409, "immutable", message); }
    public static V2Exception qualityBlocked(String message) { return new V2Exception(409, "quality_blocked", message); }
    public static V2Exception profile(String message) { return new V2Exception(403, "profile_forbidden", message); }
}
