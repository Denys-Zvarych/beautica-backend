package com.beautica.notification.channel;

import com.beautica.config.BookingSmsProperties;
import com.beautica.config.TelegramProperties;
import com.beautica.notification.sms.SmsService;
import com.beautica.notification.telegram.TelegramMessenger;
import com.beautica.notification.telegram.TelegramNotAllowListedException;
import com.beautica.notification.telegram.TelegramRecipientGoneException;
import com.beautica.notification.telegram.link.ActiveLink;
import com.beautica.notification.telegram.link.TelegramLinkService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 408 — the one seam (D1) that sends a non-urgent guest message to Telegram when that phone has an ACTIVE
 * link and to SMS otherwise, with SMS as the fallback for every Telegram failure ("never lose a message" beats
 * "never duplicate", D3).
 *
 * <h2>Resolution (D2, in order)</h2>
 * <ol>
 *   <li>{@code app.telegram.enabled=false} → {@link SmsService#send} — the exact pre-408 call, no lookup.</li>
 *   <li>{@code app.booking.sms.enabled=false} (audit-fix decision: notifications stay dark until release) →
 *       {@link SmsService#send}, which is then the no-op sender: no guest message leaves on ANY channel.</li>
 *   <li>No ACTIVE link (never linked, phone mismatch, BLOCKED) → SMS.</li>
 *   <li>Telegram send OK → done, {@code touchLastSent}; no SMS.</li>
 *   <li>{@link TelegramRecipientGoneException} → SMS, then {@code markBlocked(linkId, chatId)} (own transaction,
 *       only while the link still points at that chat; runs even when the SMS throws).</li>
 *   <li>Staging allow-list refusal ({@link TelegramNotAllowListedException}) → SMS, counted {@code not_allow_listed}.</li>
 *   <li>Any other {@code RuntimeException} on the Telegram path (transient, rate limit, back-off) → SMS at once, link
 *       untouched, no Telegram retry.</li>
 *   <li>The link lookup throws → SMS.</li>
 * </ol>
 * SMS failures propagate exactly as before, so every caller keeps its own retry / swallow semantics.
 *
 * <h2>Threading — guard</h2>
 * Never call from a request thread or inside a write transaction: the Telegram work blocks up to
 * {@code ReviewInviteProperties.TELEGRAM_LEG_WORST_CASE} (10 s: bounded gate wait + connect + read, plus the
 * post-SMS {@code markBlocked} lock wait on a 403). Production callers run on pool or scheduler threads with no transaction bound
 * (reminder fan-out pool, {@code smsSendExecutor}, the review-invite send loop). {@link #deliver} checks this:
 * a bound transaction is logged ({@code event=guest_message warning=inside_transaction}) and the message still
 * delivered — the guard reports misuse, it never costs the message. The test profile's synchronous executors run
 * the reminder fan-out inside the committing thread's after-commit callback, where the (completed) transaction is
 * still bound, so the warning is expected there. The lookup, {@code markBlocked} and {@code touchLastSent} are
 * short transactions of their own ({@code markBlocked} / {@code touchLastSent} are REQUIRES_NEW); none of them
 * wraps the network call.
 *
 * <h2>Data handling</h2>
 * Log lines and metrics carry the kind, channel and reason only — never the phone, the chat id, the text, or the
 * Telegram error description (which only lives in the exception message, never read here).
 */
@Slf4j
@Component
public class GuestMessageRouter {

    static final String METRIC = "beautica.guest_message";
    static final String CHANNEL_SMS = "sms";
    static final String CHANNEL_TELEGRAM = "telegram";
    /** Log token for {@link #deliverTelegramOnly} (not a {@link GuestMessageKind}: never SMS-routed, no counters). */
    static final String TELEGRAM_ONLY_KIND = "linked_booking_details";

    /** D7 reason tag values, each with the channel it is counted on. */
    enum Reason {
        FLAG_OFF("flag_off", CHANNEL_SMS),
        SMS_GATE_OFF("sms_gate_off", CHANNEL_SMS),
        NOT_LINKED("not_linked", CHANNEL_SMS),
        SENT("sent", CHANNEL_TELEGRAM),
        GONE_FALLBACK("gone_fallback", CHANNEL_SMS),
        NOT_ALLOW_LISTED("not_allow_listed", CHANNEL_SMS),
        TRANSIENT_FALLBACK("transient_fallback", CHANNEL_SMS),
        LOOKUP_ERROR_FALLBACK("lookup_error_fallback", CHANNEL_SMS);

        private final String token;
        private final String channel;

        Reason(String token, String channel) {
            this.token = token;
            this.channel = channel;
        }
    }

    private final SmsService smsService;
    private final TelegramMessenger telegramMessenger;
    private final TelegramLinkService linkService;
    private final TelegramProperties telegramProperties;
    private final BookingSmsProperties bookingSmsProperties;
    /** Built once (audit-fix INFO): no meter lookup / registration per message. */
    private final Map<GuestMessageKind, Map<Reason, Counter>> counters = new EnumMap<>(GuestMessageKind.class);

    public GuestMessageRouter(SmsService smsService,
                              TelegramMessenger telegramMessenger,
                              TelegramLinkService linkService,
                              TelegramProperties telegramProperties,
                              BookingSmsProperties bookingSmsProperties,
                              MeterRegistry meterRegistry) {
        this.smsService = smsService;
        this.telegramMessenger = telegramMessenger;
        this.linkService = linkService;
        this.telegramProperties = telegramProperties;
        this.bookingSmsProperties = bookingSmsProperties;
        for (GuestMessageKind kind : GuestMessageKind.values()) {
            Map<Reason, Counter> byReason = new EnumMap<>(Reason.class);
            for (Reason reason : Reason.values()) {
                byReason.put(reason, Counter.builder(METRIC)
                        .tag("kind", kind.token())
                        .tag("channel", reason.channel)
                        .tag("reason", reason.token)
                        .register(meterRegistry));
            }
            counters.put(kind, byReason);
        }
    }

    /**
     * Delivers one fully rendered guest message on the guest's channel. The Telegram text is the SMS text,
     * unchanged (D4). The caller has already applied any night rule (D5).
     *
     * @throws RuntimeException whatever {@link SmsService#send} throws when the SMS leg is used
     */
    public void deliver(GuestMessageKind kind, String phoneE164, String text) {
        if (!telegramProperties.enabled()) {
            sms(kind, Reason.FLAG_OFF, phoneE164, text);
            return;
        }
        warnIfInsideTransaction(kind);
        if (!bookingSmsProperties.getSms().isEnabled()) {
            sms(kind, Reason.SMS_GATE_OFF, phoneE164, text);
            return;
        }
        Optional<ActiveLink> link;
        try {
            link = linkService.findActiveForPhone(phoneE164);
        } catch (RuntimeException e) {
            fallback(kind, Reason.LOOKUP_ERROR_FALLBACK, phoneE164, text);
            return;
        }
        if (link.isEmpty()) {
            sms(kind, Reason.NOT_LINKED, phoneE164, text);
            return;
        }
        deliverViaTelegram(kind, link.get(), phoneE164, text);
    }

    /**
     * Phase 433 — Telegram-only send to a chat the caller already resolved (the booking-details follow-up right after
     * a fresh link). Same bounded-wait gate ({@link TelegramMessenger#sendWaiting}) and the same dark-launch gates as
     * {@link #deliver}, but no SMS fallback: the guest already has the SMS confirmation, so a failure is logged
     * (exception class only) and dropped. Same threading rule as {@link #deliver}: pool threads only.
     *
     * @return {@code true} when Telegram accepted the message; {@code false} when gated off or the send failed
     */
    public boolean deliverTelegramOnly(long chatId, String text) {
        if (!telegramProperties.enabled() || !bookingSmsProperties.getSms().isEnabled()) {
            return false;
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            log.warn("event=guest_message kind={} warning=inside_transaction", TELEGRAM_ONLY_KIND);
        }
        try {
            telegramMessenger.sendWaiting(chatId, text);
            return true;
        } catch (RuntimeException e) {
            log.warn("event=guest_message kind={} channel={} result=DROPPED cause={}", TELEGRAM_ONLY_KIND,
                    CHANNEL_TELEGRAM, e.getClass().getSimpleName());
            return false;
        }
    }

    private void deliverViaTelegram(GuestMessageKind kind, ActiveLink link, String phoneE164, String text) {
        try {
            telegramMessenger.sendWaiting(link.chatId(), text);
        } catch (TelegramRecipientGoneException e) {
            // SMS first (audit-fix cycle 2): the guest is not kept waiting on the link write; the link is blocked
            // afterwards whatever the SMS outcome (finally), so a failing SMS still never leaves a dead chat ACTIVE.
            try {
                fallback(kind, Reason.GONE_FALLBACK, phoneE164, text);
            } finally {
                markBlockedQuietly(link);
            }
            return;
        } catch (TelegramNotAllowListedException e) {
            fallback(kind, Reason.NOT_ALLOW_LISTED, phoneE164, text);
            return;
        } catch (RuntimeException e) {
            fallback(kind, Reason.TRANSIENT_FALLBACK, phoneE164, text);
            return;
        }
        count(kind, Reason.SENT);
        log.info("event=guest_message kind={} channel={} reason={}", kind.token(), CHANNEL_TELEGRAM,
                Reason.SENT.token);
        touchLastSentQuietly(link);
    }

    /** SMS without a Telegram attempt (flag off / gate off / not linked): counted, not logged per message. */
    private void sms(GuestMessageKind kind, Reason reason, String phoneE164, String text) {
        count(kind, reason);
        smsService.send(phoneE164, text);
    }

    /** SMS after a Telegram path problem: counted and logged (kind, channel, reason only). */
    private void fallback(GuestMessageKind kind, Reason reason, String phoneE164, String text) {
        count(kind, reason);
        log.warn("event=guest_message kind={} channel={} reason={}", kind.token(), CHANNEL_SMS, reason.token);
        smsService.send(phoneE164, text);
    }

    /** A failed write must not cost the message: the SMS fallback still runs. */
    private void markBlockedQuietly(ActiveLink link) {
        try {
            linkService.markBlocked(link.linkId(), link.chatId());
        } catch (RuntimeException e) {
            log.error("event=guest_message op=mark_blocked result=FAILED cause={}", e.getClass().getSimpleName());
        }
    }

    /** The message is already delivered: a failed bookkeeping write must never trigger an SMS or a retry. */
    private void touchLastSentQuietly(ActiveLink link) {
        try {
            linkService.touchLastSent(link.linkId());
        } catch (RuntimeException e) {
            log.warn("event=guest_message op=touch_last_sent result=FAILED cause={}", e.getClass().getSimpleName());
        }
    }

    /** See "Threading — guard" on the class. Reports, never blocks. */
    private static void warnIfInsideTransaction(GuestMessageKind kind) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            log.warn("event=guest_message kind={} warning=inside_transaction", kind.token());
        }
    }

    private void count(GuestMessageKind kind, Reason reason) {
        counters.get(kind).get(reason).increment();
    }
}
