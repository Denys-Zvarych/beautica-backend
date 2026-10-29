package com.beautica.moderation.event;

import com.beautica.moderation.dto.ContentReportEmailModel;
import com.beautica.moderation.service.ModerationActionLinkService;
import com.beautica.moderation.service.ModerationEmailContentLoader;
import com.beautica.notification.EmailService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Phase 414 (audit-fix PERF) — builds and sends the support report e-mail ON {@code reportEmailExecutor}, never on the
 * request thread: loads the reported content (D1/D2), mints this e-mail's one-click action links (D3/D5), then sends.
 * The request thread's after-commit listener only hands the event over, so {@code POST /reports} pays no extra
 * transactions and never holds a second pooled connection while its own is still bound.
 *
 * <p>Lossy by design, as the 395 nudge always was: any failure while preparing (database, R2 URL, ...) drops this
 * e-mail with ONE WARN line (exception type only, no stack trace, no UGC) — a returned future's failure would otherwise
 * vanish unobserved; a send failure is reported by {@link EmailService} itself. The
 * {@code content_reports} row stays the authoritative worklist.
 */
@Component
@RequiredArgsConstructor
public class ContentReportEmailDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ContentReportEmailDispatcher.class);

    private final EmailService emailService;
    private final ModerationEmailContentLoader contentLoader;
    private final ModerationActionLinkService actionLinkService;

    @Async("reportEmailExecutor")
    public CompletableFuture<Void> dispatch(String supportEmail, ContentReportCreatedEvent event) {
        ContentReportEmailModel model;
        try {
            model = buildModel(event);
        } catch (RuntimeException ex) {
            // Lossy nudge: a returned future's failure is never observed by anyone, so log it here (type only).
            log.warn("Content report {} stored; support e-mail dropped — content/link preparation failed ({})",
                    event.reportId(), ex.getClass().getSimpleName());
            return CompletableFuture.completedFuture(null);
        }
        return event.burst()
                ? emailService.sendContentReportBurstEmail(supportEmail, model)
                : emailService.sendContentReportEmail(supportEmail, model);
    }

    private ContentReportEmailModel buildModel(ContentReportCreatedEvent event) {
        ContentReportEmailModel model = contentLoader.load(event);
        return model.withActions(actionLinkService.mintFor(
                event.targetType(), event.targetId(), model.content().hiddenPending()));
    }
}
