package com.beautica.auth.phoneotp;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Commits the per-code attempt bookkeeping independently of the verify transaction (phone-first hardening B1).
 *
 * <p>A verify reserves one compare BEFORE comparing ({@link #reserveAttempt}) and, on a wrong code, records the
 * mismatch ({@link #recordMismatch}); the caller then throws a generic error that rolls back its own transaction.
 * Both must <b>survive</b> that rollback, so each is ONE autocommit statement on the dedicated 2-connection
 * {@code otpAttemptDataSource} ({@code OtpAttemptDataSourceConfig}) — never a second connection of the main pool,
 * which the caller already holds one of (audit-fix: ten concurrent verifies each waiting for an eleventh main
 * connection stalled the pool). No {@code @Transactional} here on purpose.
 *
 * <p>A bookkeeping failure (that pool busy past its 1 s wait, a statement timeout) fails CLOSED: no reservation = no
 * compare = the generic wrong-code answer. Logged by exception class only, never the phone or the code.
 */
@Component
public class PhoneOtpAttemptRecorder {

    private static final Logger log = LoggerFactory.getLogger(PhoneOtpAttemptRecorder.class);

    /** The cap sits in the WHERE clause: concurrent reservations of one row can never exceed it. */
    static final String RESERVE_SQL =
            "UPDATE phone_otps SET attempts = attempts + 1 WHERE id = ? AND used = false AND attempts < ?";

    /**
     * Counts the wrong code on the row, burns the code once the reserved compares reached the cap, and logs the failure
     * for the per-(phone, network) 24 h cap (V258) — one statement, so both land or neither does.
     */
    static final String MISMATCH_SQL = """
            WITH upd AS (
                UPDATE phone_otps SET failed_attempts = failed_attempts + 1, used = (used OR attempts >= ?)
                 WHERE id = ?
             RETURNING phone
            )
            INSERT INTO phone_otp_failures (phone, client_net)
            SELECT phone, ? FROM upd
            """;

    /**
     * Audit-fix cycles 2–3 — {@code beautica.otp.verify{purpose=any,result=record_failed,retried=true|false}}: one
     * failed write of a mismatch record. {@code retried=true} when the failure was transient and the write is tried
     * once more; {@code retried=false} when it is not retried (a non-transient failure, or the retry failed too). The
     * recorder does not know the purpose.
     */
    static final String RECORD_FAILED = "record_failed";

    private final JdbcTemplate jdbc;
    private final Counter recordFailedRetried;
    private final Counter recordFailedNotRetried;

    public PhoneOtpAttemptRecorder(@Qualifier("otpAttemptJdbcTemplate") JdbcTemplate otpAttemptJdbcTemplate,
                                   MeterRegistry meterRegistry) {
        this.jdbc = otpAttemptJdbcTemplate;
        this.recordFailedRetried = recordFailedCounter(meterRegistry, true);
        this.recordFailedNotRetried = recordFailedCounter(meterRegistry, false);
    }

    private static Counter recordFailedCounter(MeterRegistry meterRegistry, boolean retried) {
        return Counter.builder(PhoneOtpService.VERIFY_METRIC)
                .tag("purpose", "any")
                .tag("result", RECORD_FAILED)
                .tag("retried", String.valueOf(retried))
                .register(meterRegistry);
    }

    /**
     * @param cap the per-code compare ceiling ({@link PhoneOtp#MAX_VERIFY_ATTEMPTS} by default)
     * @return {@code true} when one compare was reserved; {@code false} when the code is used, capped or gone, or the
     *         reservation could not be written
     */
    public boolean reserveAttempt(Long otpId, int cap) {
        try {
            return jdbc.update(RESERVE_SQL, otpId, cap) == 1;
        } catch (DataAccessException e) {
            log.warn("OTP attempt reservation failed: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * A lost mismatch record is a lost failure for the per-(phone, network) daily cap, so a TRANSIENT failure (no pool
     * connection, a transient DB error) gets exactly one more try. A non-transient failure — the 5 s statement timeout
     * ({@link QueryTimeoutException}), a constraint or SQL error — is not retried: a second try would hold the pool as
     * long again for the same result. Every failed try is counted ({@value #RECORD_FAILED}); a dropped record is logged
     * by exception class only (the reserved compare already counted against the per-code cap).
     *
     * @param clientNet the caller's network key ({@code ClientIpResolver.prefixKey}, or {@code "unknown"})
     */
    public void recordMismatch(Long otpId, int cap, String clientNet) {
        DataAccessException first = tryRecordMismatch(otpId, cap, clientNet);
        if (first == null) {
            return;
        }
        if (!isTransient(first)) {
            recordFailedNotRetried.increment();
            log.warn("OTP mismatch record dropped, not retried: {}", first.getClass().getSimpleName());
            return;
        }
        recordFailedRetried.increment();
        DataAccessException second = tryRecordMismatch(otpId, cap, clientNet);
        if (second != null) {
            recordFailedNotRetried.increment();
            log.warn("OTP mismatch record dropped after retry: {}", second.getClass().getSimpleName());
        }
    }

    /** @return the failure, or {@code null} when the record was written */
    private DataAccessException tryRecordMismatch(Long otpId, int cap, String clientNet) {
        try {
            jdbc.update(MISMATCH_SQL, cap, otpId, clientNet);
            return null;
        } catch (DataAccessException e) {
            return e;
        }
    }

    /**
     * No connection, or a transient DB error — but NOT {@link QueryTimeoutException}, which Spring classes as transient
     * yet here means the 5 s statement timeout already ran in full.
     */
    private static boolean isTransient(DataAccessException e) {
        return e instanceof CannotGetJdbcConnectionException
                || (e instanceof TransientDataAccessException && !(e instanceof QueryTimeoutException));
    }
}
