package com.beautica.auth.appcheck;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * B514 (AUTH-54) — marks a controller method that ALWAYS sends an SMS: {@link AppCheckInterceptor} runs
 * {@link AppCheckGuard#check} before the handler (after the Bucket4j per-IP filter, so the rate limit stays the
 * first layer). Endpoints that send an SMS on one branch only (B516 forgot / change password) call the guard from
 * the service instead. The annotated set is pinned by the path-ledger test in {@code AppCheckGuardIT}: B514
 * {@code POST /api/v1/auth/register/phone/otp/send}; B516 {@code POST /api/v1/users/me/phone/otp/send}; B520 {@code POST /api/v1/auth/invite} and
 * {@code POST /api/v1/salons/{salonId}/invite}.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireAppCheck {
}
