package com.smartservice.api;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * P0-4/P2-2: 全局异常处理
 * 统一捕获业务异常、参数校验异常与未知异常，返回结构化错误
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Void> handleBusinessException(BusinessException e) {
        log.warn("Business error: {}", e.getMessage());
        // 返回业务自定义 message（如"用户名已存在"），code 保留给程序判断
        return ApiResponse.error(e.getErrorCode().code(), e.getMessage());
    }

    /**
     * P2-2: Bean Validation 校验失败（@Valid 注解触发）
     * 取第一个字段错误的具体 message 返回
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Void> handleValidation(MethodArgumentNotValidException e) {
        FieldError fe = e.getBindingResult().getFieldError();
        String msg = fe != null && fe.getDefaultMessage() != null
            ? fe.getDefaultMessage() : "请求参数不合法";
        log.warn("Validation error: {}", msg);
        return ApiResponse.error(ApiResponse.ErrorCode.BAD_REQUEST.code(), msg);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleUnreadable(HttpMessageNotReadableException e) {
        log.warn("Malformed request body: {}", e.getMessage());
        return ApiResponse.error(ApiResponse.ErrorCode.BAD_REQUEST);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("Invalid argument: {}", e.getMessage());
        return ApiResponse.error(ApiResponse.ErrorCode.BAD_REQUEST);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleUnknown(Exception e) {
        log.error("Unexpected error", e);
        return ApiResponse.error(ApiResponse.ErrorCode.INTERNAL_ERROR);
    }
}
