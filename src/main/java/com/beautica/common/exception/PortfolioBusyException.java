package com.beautica.common.exception;

import org.springframework.http.HttpStatus;

/**
 * A portfolio upload could not take the owning salon/master row lock within its 3s
 * {@code lock_timeout} ({@code 55P03}) — another upload or a salon write holds it (audit cycle 1, F6a).
 *
 * <p>Translated by {@code MediaService} from the {@code PessimisticLockingFailureException} raised on the
 * portfolio upload path ONLY, and surfaced as {@code 503} + {@code Retry-After} by
 * {@link GlobalExceptionHandler}. The global {@code 55P03 -> 409} mapping other endpoints rely on is
 * untouched; the point of the distinct status is that a 409 on a portfolio upload now always means
 * {@link PortfolioLimitReachedException}, never transient contention.
 */
public class PortfolioBusyException extends BusinessException {

    /** Seconds the client should wait before retrying — a little under the 3s lock timeout it lost. */
    public static final long RETRY_AFTER_SECONDS = 2;

    public PortfolioBusyException() {
        super(HttpStatus.SERVICE_UNAVAILABLE, ServiceUnavailableMessages.PORTFOLIO_BUSY);
    }

    public long getRetryAfterSeconds() {
        return RETRY_AFTER_SECONDS;
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
