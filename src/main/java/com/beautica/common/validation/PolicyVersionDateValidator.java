package com.beautica.common.validation;

import com.beautica.common.TimeZones;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

/**
 * Validator behind {@link PolicyVersionDate}. Under Spring the {@link Clock} bean is injected (so tests can pin
 * "today"); a slice without one, or a plain Hibernate Validator, falls back to the system clock. "Today" is the
 * Europe/Kyiv civil date; a policy effective today is valid, tomorrow's is not.
 */
public class PolicyVersionDateValidator implements ConstraintValidator<PolicyVersionDate, String> {

    private static final int DATE_LENGTH = 10;
    private static final DateTimeFormatter STRICT_DATE =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    private final Clock kyivClock;

    /** Plain Bean Validation (no Spring): system clock. */
    public PolicyVersionDateValidator() {
        this.kyivClock = Clock.system(TimeZones.KYIV);
    }

    @Autowired
    public PolicyVersionDateValidator(ObjectProvider<Clock> clock) {
        this.kyivClock = clock.getIfAvailable(Clock::systemUTC).withZone(TimeZones.KYIV);
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.length() < DATE_LENGTH) {
            return true; // @NotBlank / @Pattern report these
        }
        try {
            return !LocalDate.parse(value.substring(0, DATE_LENGTH), STRICT_DATE).isAfter(LocalDate.now(kyivClock));
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
