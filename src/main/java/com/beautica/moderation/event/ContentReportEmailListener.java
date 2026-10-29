package com.beautica.moderation.event;

import com.beautica.config.SupportProperties;
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
 * e-mails and an idempotent repeat (no event) never e-mails twice. Since the phase-414 audit-fix the listener only
 * hands the event to {@link ContentReportEmailDispatcher} on the bounded {@code reportEmailExecutor}; the content load,
 * the link minting and the send all run there, never on the request thread. When that pool is saturated the hand-off
 * is rejected ({@link TaskRejectedException}); the nudge is dropped with ONE WARN line (no stack trace) instead of an
 * ERROR-level after-commit failure. Lossy by design: the {@code content_reports} row is authoritative.
 *
 * <p>An unconfigured {@code SUPPORT_EMAIL} skips the e-mail instead of failing the report: the report is
 * already stored and the 396 queue is the real worklist.
 */
@Component
@RequiredArgsConstructor
public class ContentReportEmailListener {

    private static final Logger log = LoggerFactory.getLogger(ContentReportEmailListener.class);

    private final SupportProperties supportProperties;
    private final ContentReportEmailDispatcher dispatcher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onContentReportCreated(ContentReportCreatedEvent event) {
        String supportEmail = supportProperties.getEmail();
        if (!StringUtils.hasText(supportEmail)) {
            log.warn("Content report {} stored; support e-mail skipped — SUPPORT_EMAIL is not configured",
                    event.reportId());
            return;
        }
        try {
            dispatcher.dispatch(supportEmail, event);
        } catch (TaskRejectedException ex) {
            log.warn("Content report {} stored; support e-mail dropped — reportEmailExecutor saturated",
                    event.reportId());
        }
    }
}
