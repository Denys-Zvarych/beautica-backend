package com.beautica.notification.service;

import com.beautica.booking.entity.Booking;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.notification.entity.NotificationOutboxEntry;
import com.beautica.notification.entity.OutboxEventType;
import com.beautica.notification.entity.OutboxStatus;
import com.beautica.notification.repository.NotificationOutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationOutboxDrainWorker {

    private static final int BATCH_SIZE = 50;
    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_ERROR_LENGTH = 500;

    /**
     * Redacts URL query strings, JWT-shaped values, and Bearer header values from
     * exception messages before persisting them to last_error. Compiled once at class
     * load — never per invocation (Fix M3 / Security MEDIUM).
     */
    private static final Pattern SENSITIVE_PATTERN = Pattern.compile(
            "\\?[^\\s]+" +
            "|[A-Za-z0-9_\\-]{20,}\\.[A-Za-z0-9_\\-]{20,}\\.[A-Za-z0-9_\\-]{20,}" +
            "|(?i)bearer\\s+[A-Za-z0-9_\\-.]+"
    );

    private final NotificationOutboxRepository outboxRepository;
    private final NotificationService notificationService;
    private final BookingRepository bookingRepository;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelay = 5_000)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void drain() {
        List<NotificationOutboxEntry> batch = outboxRepository.claimPendingBatch(BATCH_SIZE);
        if (batch.isEmpty()) return;

        // Pre-load all booking IDs in one query to avoid N+1 (Fix Perf HIGH).
        Set<UUID> bookingIds = batch.stream()
                .filter(e -> e.getEventType() != OutboxEventType.INVITE)
                .map(NotificationOutboxEntry::getAggregateId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<UUID, Booking> bookingCache = bookingRepository.findAllByIdsWithGraph(new ArrayList<>(bookingIds))
                .stream()
                .collect(Collectors.toMap(Booking::getId, b -> b));

        for (NotificationOutboxEntry entry : batch) {
            try {
                dispatch(entry, bookingCache);
                entry.setStatus(OutboxStatus.SENT);
            } catch (Exception e) {
                int next = entry.getAttempts() + 1;
                entry.setAttempts(next);
                entry.setLastError(sanitizeAndTruncate(e.getMessage(), MAX_ERROR_LENGTH));
                entry.setStatus(next >= MAX_ATTEMPTS ? OutboxStatus.DEAD : OutboxStatus.PENDING);
                log.warn("Outbox dispatch failed [{}] attempt {}/{}: {}",
                        entry.getId(), next, MAX_ATTEMPTS, e.getClass().getSimpleName());
            }
            // No explicit save() — JPA dirty-checking flushes on transaction commit.
        }
    }

    private void dispatch(NotificationOutboxEntry entry, Map<UUID, Booking> bookingCache) {
        switch (entry.getEventType()) {
            case NEW_BOOKING      -> notificationService.notifyNewBooking(getBooking(entry, bookingCache));
            case STATUS_CHANGED   -> notificationService.notifyBookingStatusChanged(getBooking(entry, bookingCache));
            case CLIENT_CANCELLED -> notificationService.notifyClientCancelled(getBooking(entry, bookingCache));
            case INVITE -> {
                Map<String, String> p = readJson(entry.getPayload());
                // aggregateId = inviteTokenId UUID; Phase 5.11 NotificationService resolves real URL.
                notificationService.sendInviteEmail(
                        p.get("email"),
                        entry.getAggregateId().toString(),
                        p.get("salonName")
                );
            }
        }
    }

    private Booking getBooking(NotificationOutboxEntry entry, Map<UUID, Booking> cache) {
        Booking booking = cache.get(entry.getAggregateId());
        if (booking == null) {
            throw new IllegalStateException("Booking not found for outbox entry: " + entry.getId());
        }
        return booking;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> readJson(String payload) {
        try {
            // TypeReference forces Jackson to validate that every value is a String.
            // Raw Map.class would produce Map<String,Object>, allowing nested objects
            // to reach callers and produce a late ClassCastException whose message
            // includes the full nested representation (Security MEDIUM).
            return objectMapper.readValue(payload,
                    new TypeReference<Map<String, String>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot deserialize outbox payload", e);
        }
    }

    /**
     * Strips URL query strings, JWT-shaped tokens, and Bearer header values to prevent
     * secrets appearing in last_error, then truncates to the DB column limit.
     * Uses a pre-compiled Pattern (see SENSITIVE_PATTERN) — never compiled per call.
     */
    private String sanitizeAndTruncate(String msg, int max) {
        if (msg == null) return null;
        String sanitized = SENSITIVE_PATTERN.matcher(msg).replaceAll("[REDACTED]");
        return sanitized.length() <= max ? sanitized : sanitized.substring(0, max);
    }
}
