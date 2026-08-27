package com.beautica.notification.sms;

import lombok.extern.slf4j.Slf4j;

/**
 * The {@link SmsService} the application runs with while {@code app.booking.sms.enabled} is
 * {@code false} — which is the default, and the state every committed profile ships in
 * (Phase 22.7).
 *
 * <h2>Why the gate lives here and not at the call sites</h2>
 * Booking SMS costs real money, and the release decision to start spending it is a single operator
 * action, not a code change. Selecting this bean instead of {@link TurbosmsService} gates
 * <b>every</b> current and future sender — guest confirmation, guest reminder, guest cancellation,
 * provider decline, walk-in confirmation, phone OTP — for free, and makes it impossible for a new
 * call site to forget the flag. See {@code SmsConfig} for the selection itself.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li><b>Never throws.</b> A disabled feature is not a failure. Callers already treat a thrown
 *       {@link SmsDeliveryException} as "warn and carry on", but a gate that threw would fill
 *       production logs with warnings describing a deliberate state.</li>
 *   <li><b>Never touches the network</b> and never enqueues anything for a later retry — a
 *       suppressed message is dropped, not deferred. Flipping the flag on must not release a
 *       backlog of stale appointment reminders.</li>
 *   <li><b>Logs exactly one INFO line</b>, carrying the {@link PhoneMask masked} recipient and
 *       <b>never</b> {@code text}. The body holds a client's master, service, date and time —
 *       PII that Anti-Bug §I forbids in logs whether or not the message was actually sent.</li>
 *   <li>Returns {@code void} exactly as a successful send does, so no caller needs a branch and no
 *       caller can distinguish "suppressed" from "delivered". That symmetry is what lets Phase
 *       22.7 ship the walk-in send with no reference to the flag at its call site.</li>
 * </ul>
 */
@Slf4j
public class NoOpSmsService implements SmsService {

    /**
     * Records the suppression and returns.
     *
     * @param phoneE164 recipient — logged masked only
     * @param text      message body — deliberately unused and never logged
     */
    @Override
    public void send(String phoneE164, String text) {
        log.info("SMS suppressed (app.booking.sms.enabled=false) to={}", PhoneMask.mask(phoneE164));
    }
}
