package com.beautica.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Exempts a single test method (or a whole class) from {@link SlowTestExtension}'s 10-second
 * wall-clock ceiling.
 *
 * <p><b>Use this ONLY when the wall clock is measuring the wrong thing.</b> The ceiling exists to
 * stop a test quietly growing into a minute of CI time; it is a budget, not a correctness
 * assertion. A handful of tests deliberately seed a LARGE fixture because the size IS the
 * experiment — a statement-count gate that must cross a batch boundary at 50 and 51 rows, say —
 * and for those the honest reading of a red SlowTestExtension is "this machine was busy", not
 * "the code regressed". Shrinking such a fixture to fit the budget would delete the very property
 * under test.
 *
 * <p>It is NOT a licence to silence a genuinely slow test. If the assertion under the clock is
 * about latency, or the test is slow because the production path is slow, fix the path or the
 * fixture instead. Every use must carry a {@code reason} that says why the wall clock is not the
 * measurement.
 *
 * @see SlowTestExtension
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
@Inherited
public @interface NotATimedTest {

    /** Why the wall-clock ceiling is measuring the wrong thing for this test. */
    String reason();
}
