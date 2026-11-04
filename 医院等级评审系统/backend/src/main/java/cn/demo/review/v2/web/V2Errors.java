package cn.demo.review.v2.web;

import cn.demo.review.v2.V2Exception;
import cn.demo.review.v2.platform.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.LinkedHashMap;
import java.util.Map;

/** v2 错误体：{code,message,requestId,details}。消息不含内部路径与堆栈。 */
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = {"cn.demo.review.v2.web", "cn.demo.review.v3.web", "cn.demo.review.auth"})
public class V2Errors {
    private static final Logger log = LoggerFactory.getLogger(V2Errors.class);

    @ExceptionHandler(V2Exception.class)
    ResponseEntity<Map<String, Object>> business(V2Exception e, HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", e.code);
        body.put("message", e.getMessage());
        body.put("requestId", RequestIdFilter.of(request));
        body.put("details", e.details);
        return ResponseEntity.status(e.status).body(body);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, Object>> tooLarge(HttpServletRequest request) {
        return body(400, "validation_failed", "附件最大5MiB", request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unexpected(Exception e, HttpServletRequest request) {
        log.error("v0.2 operation failed requestId={}", RequestIdFilter.of(request), e);
        return body(500, "internal_error", "服务端处理失败，请凭请求编号排查", request);
    }

    private static ResponseEntity<Map<String, Object>> body(int status, String code, String message,
                                                            HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("requestId", RequestIdFilter.of(request));
        body.put("details", java.util.List.of());
        return ResponseEntity.status(status).body(body);
    }
}
