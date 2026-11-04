package cn.demo.review.web;

import cn.demo.review.service.BusinessException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.slf4j.*;
import java.util.Map;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger log=LoggerFactory.getLogger(ApiErrors.class);
    @ExceptionHandler(cn.demo.review.v2.V2Exception.class) ResponseEntity<?> authorization(cn.demo.review.v2.V2Exception e){return ResponseEntity.status(e.status).body(Map.of("message",e.getMessage()));}
    @ExceptionHandler(BusinessException.class) ResponseEntity<?> business(BusinessException e){return ResponseEntity.status(e.status()).body(Map.of("message",e.getMessage()));}
    @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class,MissingServletRequestParameterException.class,MissingServletRequestPartException.class}) ResponseEntity<?> malformed(Exception e){return ResponseEntity.badRequest().body(Map.of("message","请求格式不正确或缺少必填字段"));}
    @ExceptionHandler(MaxUploadSizeExceededException.class) ResponseEntity<?> size(){return ResponseEntity.badRequest().body(Map.of("message","附件最大5MiB"));}
    @ExceptionHandler(NoResourceFoundException.class) ResponseEntity<?> missing(){return ResponseEntity.status(404).body(Map.of("message","接口或资源不存在"));}
    @ExceptionHandler(Exception.class) ResponseEntity<?> unexpected(Exception e){log.error("Local demo operation failed",e);return ResponseEntity.status(500).body(Map.of("message","本地演示操作失败，请查看后端日志"));}
}
