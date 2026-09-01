package com.smartservice.api;

/**
 * P0-4: 统一响应体
 * 所有接口返回 { code, message, data } 结构
 */
public record ApiResponse<T>(int code, String message, T data) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(0, "success", data);
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode) {
        return new ApiResponse<>(errorCode.code(), errorCode.message(), null);
    }

    /**
     * 业务错误码
     */
    public enum ErrorCode {
        BAD_REQUEST(40000, "请求参数不合法"),
        UNAUTHORIZED(40100, "未认证或凭证无效"),
        RATE_LIMITED(42900, "请求过于频繁，请稍后再试"),
        LLM_UNAVAILABLE(50300, "LLM 服务暂不可用，请稍后重试"),
        INTERNAL_ERROR(50000, "服务内部错误");

        private final int code;
        private final String message;

        ErrorCode(int code, String message) {
            this.code = code;
            this.message = message;
        }

        public int code() {
            return code;
        }

        public String message() {
            return message;
        }
    }
}
