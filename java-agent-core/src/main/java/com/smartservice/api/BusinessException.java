package com.smartservice.api;

import lombok.Getter;

/**
 * P0-4: 业务异常（core）
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
