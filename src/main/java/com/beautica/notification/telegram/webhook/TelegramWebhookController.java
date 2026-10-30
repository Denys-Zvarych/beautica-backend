package com.beautica.notification.telegram.webhook;

import com.beautica.config.TelegramEnabledCondition;
import com.beautica.notification.telegram.webhook.TelegramBotReplies.Reply;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.Semaphore;

/**
 * Phase 406 D1–D6 — {@code POST /api/v1/telegram/webhook}, Telegram's only way in.
 *
 * <ol>
 *   <li>Secret header check first ({@link TelegramWebhookSecretVerifier}): 401 / 429, empty body, body never read.</li>
 *   <li>Body capped at {@value #MAX_BODY_BYTES} bytes, read by hand so an oversized one is refused (413) before it
 *       is parsed.</li>
 *   <li>An authenticated update is always 200 (D6): unparseable or unsupported ones are a no-op; a reply, if any,
 *       is the response body ({@code sendMessage}).</li>
 *   <li>At most {@value #MAX_CONCURRENT_UPDATES} updates are handled at once (406 perf audit: Telegram may open
 *       several connections and the Hikari pool is 10). A call that finds every slot taken is answered 200 with
 *       the «try again» reply ({@link TelegramUpdateHandler#busyReply}) — no wait, no DB access.</li>
 *   <li>An exception from the handler is logged by its class name only — never left to
 *       {@code GlobalExceptionHandler}, whose full-trace log could carry a Postgres {@code Detail} with a chat id
 *       (406 security audit). A poison update ({@link DataIntegrityViolationException},
 *       {@link IllegalArgumentException}, {@link IllegalStateException}: redelivery would fail the same way) is
 *       answered 200 with no reply so Telegram drops it; anything else is treated as transient: empty 500, Telegram
 *       redelivers.</li>
 * </ol>
 *
 * <p>The bean exists only when {@code app.telegram.enabled} binds {@code true} ({@link TelegramEnabledCondition}),
 * so a dark deploy answers 404. Hidden from the OpenAPI spec: no client calls it.
 */
@Slf4j
@Hidden
@RestController
@RequestMapping("/api/v1/telegram")
@Conditional(TelegramEnabledCondition.class)
public class TelegramWebhookController {

    static final int MAX_BODY_BYTES = 64 * 1024;
    /** Below Telegram's {@code max_connections} headroom and well below the 10-connection Hikari pool. */
    static final int MAX_CONCURRENT_UPDATES = 4;

    private final TelegramWebhookSecretVerifier secretVerifier;
    private final TelegramUpdateHandler updateHandler;
    private final ObjectMapper objectMapper;
    private final Semaphore updateSlots;

    @Autowired
    public TelegramWebhookController(TelegramWebhookSecretVerifier secretVerifier,
                                     TelegramUpdateHandler updateHandler, ObjectMapper objectMapper) {
        this(secretVerifier, updateHandler, objectMapper, new Semaphore(MAX_CONCURRENT_UPDATES));
    }

    /** Tests pass a pre-drained semaphore to exercise the busy path. */
    TelegramWebhookController(TelegramWebhookSecretVerifier secretVerifier, TelegramUpdateHandler updateHandler,
                              ObjectMapper objectMapper, Semaphore updateSlots) {
        this.secretVerifier = secretVerifier;
        this.updateHandler = updateHandler;
        this.objectMapper = objectMapper;
        this.updateSlots = updateSlots;
    }

    @PostMapping("/webhook")
    public ResponseEntity<Reply> receive(HttpServletRequest request) throws IOException {
        switch (secretVerifier.verify(request)) {
            case REJECTED -> {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
            case THROTTLED -> {
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
            }
            case ACCEPTED -> { }
        }
        Optional<byte[]> body = readBounded(request);
        if (body.isEmpty()) {
            log.warn("event=telegram_webhook_rejected reason=body_too_large");
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        }
        Optional<TelegramUpdate> update = parse(body.get());
        if (update.isEmpty()) {
            return ResponseEntity.ok().build();
        }
        if (!updateSlots.tryAcquire()) {
            return toResponse(updateHandler.busyReply(update.get()));
        }
        try {
            return toResponse(updateHandler.handle(update.get()));
        } catch (DataIntegrityViolationException | IllegalArgumentException | IllegalStateException poison) {
            log.error("event=telegram_update type=dropped result={}", poison.getClass().getSimpleName());
            return ResponseEntity.ok().build();
        } catch (RuntimeException unexpected) {
            log.error("event=telegram_update type=error result={}", unexpected.getClass().getSimpleName());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        } finally {
            updateSlots.release();
        }
    }

    private static ResponseEntity<Reply> toResponse(Optional<Reply> reply) {
        return reply
                .map(r -> ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(r))
                .orElseGet(() -> ResponseEntity.ok().build());
    }

    private Optional<TelegramUpdate> parse(byte[] body) {
        try {
            return Optional.ofNullable(objectMapper.readValue(body, TelegramUpdate.class));
        } catch (JsonProcessingException malformed) {
            // Never Telegram's own output; 200 so a broken update cannot be redelivered forever.
            log.info("event=telegram_update type=ignored result=malformed");
            return Optional.empty();
        } catch (IOException unreadable) {
            log.info("event=telegram_update type=ignored result=unreadable");
            return Optional.empty();
        }
    }

    /** At most {@link #MAX_BODY_BYTES}; empty when the declared or actual length is larger. */
    private static Optional<byte[]> readBounded(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            return Optional.empty();
        }
        byte[] bytes = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        return bytes.length > MAX_BODY_BYTES ? Optional.empty() : Optional.of(bytes);
    }
}
