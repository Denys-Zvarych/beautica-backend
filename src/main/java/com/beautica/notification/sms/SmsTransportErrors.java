package com.beautica.notification.sms;

import org.springframework.web.client.ResourceAccessException;

import javax.net.ssl.SSLHandshakeException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.util.concurrent.CancellationException;

/**
 * Phase 439 — the transport-failure classification shared by every paid SMS adapter ({@link TurbosmsService},
 * {@link SmsClubService}). Extracted verbatim from {@code TurbosmsService#isOutcomeUnknown} (phase 400) so the two
 * providers cannot drift into two different definitions of "the request may have reached the provider".
 *
 * <p>Pre-send failures — connection refused, connect timeout, unknown host, no route, failed TLS handshake — all
 * happen before the request is written, so they are definite (nothing was sent, nothing billed). Any other I/O
 * failure (read timeout, connection dropped after the write) leaves the provider's verdict unknown.
 */
final class SmsTransportErrors {

    /** Cause-chain walk bound in {@link #isOutcomeUnknown}; real chains are 2-4 deep. */
    static final int MAX_CAUSE_DEPTH = 16;

    private SmsTransportErrors() {
    }

    /** {@code true} when this single throwable (not its causes) is a failure that happens before the request is written. */
    static boolean isPreSend(Throwable t) {
        return t instanceof ConnectException || t instanceof HttpConnectTimeoutException
                || t instanceof UnknownHostException || t instanceof NoRouteToHostException
                || t instanceof SSLHandshakeException;
    }

    /**
     * {@code true} when the request may have reached the provider: an I/O failure ({@link ResourceAccessException})
     * with no pre-send cause in its chain.
     *
     * <p>Depth-capped (phase 400 audit cycle 2): a cyclic cause chain longer than self-reference (A -> B -> A) would
     * spin forever. Hitting the cap with no pre-send cause found reads as UNKNOWN — the safe side (never retried).
     */
    static boolean isOutcomeUnknown(RuntimeException e) {
        if (!(e instanceof ResourceAccessException)) {
            return false;
        }
        int depth = 0;
        for (Throwable t = e; t != null && depth < MAX_CAUSE_DEPTH;
                t = t.getCause() == t ? null : t.getCause(), depth++) {
            if (isPreSend(t)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Phase 439 audit cycle 2 — {@link #isOutcomeUnknown(RuntimeException)} plus one additive guard, used by both
     * {@link SmsClubService} and {@link TurbosmsService} (TurboSMS switched to it in phase 441).
     *
     * <p>Spring 6.2.6 {@code JdkClientHttpRequest} enforces the read timeout by cancelling the response future; when
     * the cancel wins the race, {@code future.get()} throws a bare {@link CancellationException} that RestClient does
     * not wrap in {@link ResourceAccessException}. The timeout fires only after the request was handed to the client,
     * so it is a "maybe sent" failure: a {@link CancellationException} anywhere in the depth-capped chain with no
     * pre-send cause reads as UNKNOWN (never retried, never reported as definitely not sent).
     */
    static boolean isOutcomeUnknownOrCancelled(RuntimeException e) {
        if (isOutcomeUnknown(e)) {
            return true;
        }
        boolean cancelled = false;
        int depth = 0;
        for (Throwable t = e; t != null && depth < MAX_CAUSE_DEPTH;
                t = t.getCause() == t ? null : t.getCause(), depth++) {
            if (isPreSend(t)) {
                return false;
            }
            cancelled |= t instanceof CancellationException;
        }
        return cancelled;
    }
}
