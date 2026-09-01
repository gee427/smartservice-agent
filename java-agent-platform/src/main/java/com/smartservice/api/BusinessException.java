package com.smartservice.api;

import lombok.Getter;

/**
 * P0-4: 业务异常
 * 业务层主动抛出，由 GlobalExceptionHandler 统一转成 ApiResponse
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ApiResponse.ErrorCode errorCode;

    public BusinessException(ApiResponse.ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    public BusinessException(ApiResponse.ErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = errorCode;
    }
}
