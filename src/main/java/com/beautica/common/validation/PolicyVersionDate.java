package com.beautica.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A privacy-policy version string ({@code YYYY-MM-DD[.N]}, phase 381) whose date part must be a REAL calendar
 * date that is not in the future in Europe/Kyiv (server clock). Complements the format {@code @Pattern}: that
 * accepts {@code 9999-99-99}; this does not. {@code null}/short values are left to the other constraints.
 */
@Documented
@Constraint(validatedBy = PolicyVersionDateValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface PolicyVersionDate {

    String message() default "Privacy policy version must be a real date that is not in the future";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
