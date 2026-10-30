package com.beautica.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Phase 431 (user decision 2026-10-30) — a provider tried a 6th reschedule of the same GUEST booking or
 * visit ({@code client_id IS NULL}) within a rolling 24 h. Rejected before any change; the mobile app
 * branches on {@code data.code} = {@link #ERROR_CODE} to show «Ви досягли ліміту перенесень для цього
 * запису. Зверніться в підтримку.». Same 409 + coded-body shape as {@link PortfolioLimitReachedException}.
 */
public class RescheduleLimitReachedException extends BusinessException {

    public static final String ERROR_CODE = "RESCHEDULE_LIMIT_REACHED";

    public RescheduleLimitReachedException() {
        super(HttpStatus.CONFLICT, "Reschedule limit reached for this booking");
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
