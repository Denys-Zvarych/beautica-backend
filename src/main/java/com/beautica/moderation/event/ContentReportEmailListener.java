package com.beautica.moderation.event;

import com.beautica.config.SupportProperties;
import com.beautica.notification.EmailService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;

/**
 * Phase 395 D7 — tells support about a new report once its row has COMMITTED, so a rolled-back insert never
 * e-mails and an idempotent repeat (no event) never e-mails twice. The send itself is {@code @Async} on
 * {@code reportEmailExecutor}; it is lossy by design because the {@code content_reports} row is authoritative.
 * When that bounded pool is saturated the hand-off is rejected ({@link TaskRejectedException}); the nudge is
 * dropped with ONE WARN line (no stack trace) instead of an ERROR-level after-commit failure.
 *
 * <p>An unconfigured {@code SUPPORT_EMAIL} skips the e-mail instead of failing the report: the report is
 * already stored and the 396 queue is the real worklist.
 */
@Component
@RequiredArgsConstructor
public class ContentReportEmailListener {

    private static final Logger log = LoggerFactory.getLogger(ContentReportEmailListener.class);

    private final SupportProperties supportProperties;
    private final EmailService emailService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onContentReportCreated(ContentReportCreatedEvent event) {
        String supportEmail = supportProperties.getEmail();
        if (!StringUtils.hasText(supportEmail)) {
            log.warn("Content report {} stored; support e-mail skipped — SUPPORT_EMAIL is not configured",
                    event.reportId());
            return;
        }
        try {
            if (event.burst()) {
                emailService.sendContentReportBurstEmail(
                        supportEmail,
                        event.reportId(),
                        event.targetType().name(),
                        event.targetId(),
                        event.reason().name(),
                        event.reporterUserId());
            } else {
                emailService.sendContentReportEmail(
                        supportEmail,
                        event.reportId(),
                        event.targetType().name(),
                        event.targetId(),
                        event.reason().name(),
                        event.reporterUserId());
            }
        } catch (TaskRejectedException ex) {
            log.warn("Content report {} stored; support e-mail dropped — reportEmailExecutor saturated",
                    event.reportId());
        }
    }
}
