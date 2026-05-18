package com.beautica.common.exception;

import org.springframework.http.HttpStatus;

public class VerificationException extends RuntimeException {

    public enum Code { INVALID_CODE, CODE_EXPIRED, ALREADY_VERIFIED }

    private final Code code;

    public VerificationException(Code code) {
        super(code.name());
        this.code = code;
    }

    public Code getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return HttpStatus.BAD_REQUEST;
    }
}
