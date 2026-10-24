package com.beautica.notification.sms;

import java.util.concurrent.RejectedExecutionException;

/**
 * Phase 390 — the outbox-path SMS hand-off ({@code BookingSmsDispatcher#dispatchOrThrow}) was refused
 * because {@code smsSendExecutor} is saturated, or because accepting it would eat into the queue
 * headroom reserved for request-path SMS.
 *
 * <p>A {@link RejectedExecutionException} subtype on purpose: it is backpressure, not a delivery
 * failure, so any catch that already treats a rejection as "re-queue without counting an attempt"
 * stays correct. The distinct type lets {@code NotificationOutboxDrainWorker} scope the deferral to
 * the batch's SMS rows only, instead of re-queueing the e-mail rows behind it too.
 *
 * <p>The message never carries a recipient or a rendered body (Anti-Bug §I).
 */
public class SmsBackpressureException extends RejectedExecutionException {

    public SmsBackpressureException(String message) {
        super(message);
    }

    public SmsBackpressureException(String message, Throwable cause) {
        super(message, cause);
    }
}
