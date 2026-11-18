package com.beautica.user.phone;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * B516 (AUTH-45) — marks an APP booking-create handler: with {@code APP_AUTH_REQUIRE_VERIFIED_PHONE=true} a CLIENT
 * without a verified phone gets 409 {@code PHONE_NOT_VERIFIED} before the handler runs
 * ({@link VerifiedPhoneGateInterceptor}). Today: {@code POST /api/v1/bookings} and {@code POST /api/v1/appointments}.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireVerifiedPhone {
}
