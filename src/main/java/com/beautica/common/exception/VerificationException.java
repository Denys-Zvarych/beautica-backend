package com.beautica.common.exception;

import org.springframework.http.HttpStatus;

public class VerificationException extends RuntimeException {

    /**
     * {@code CURRENT_PASSWORD_INVALID} (B516, AUTH-D19): the verify step of a phone change / email add or change got a
     * wrong or missing current password — a 400 (never 401, the app must not sign out) that says nothing about the code.
     */
    public enum Code { INVALID_CODE, CODE_EXPIRED, ALREADY_VERIFIED, CURRENT_PASSWORD_INVALID }

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
