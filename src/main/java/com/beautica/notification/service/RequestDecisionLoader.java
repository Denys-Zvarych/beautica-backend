package com.beautica.notification.service;

import com.beautica.service.entity.PlatformCategory;
import com.beautica.service.entity.ServiceTypeSuggestion;
import com.beautica.service.repository.PlatformCategoryRepository;
import com.beautica.service.repository.ServiceTypeSuggestionRepository;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Phase 491 — the drain worker's pre-load for {@code REQUEST_DECIDED} rows: the decided requests and
 * their recipients for a whole batch in at most three statements, before the dispatch loop starts (the
 * drain worker holds no connection while SMTP is in flight). Mirrors {@code InAppPushDispatcher#prepare}
 * and reads the same repositories {@code NotificationViewAssembler} reads for the feed copy.
 *
 * <p>Absence is data, not an error: a request or user missing from the snapshot is a D3 skip decided by
 * {@link NotificationService#notifyRequestDecided}.
 */
@Component
@RequiredArgsConstructor
public class RequestDecisionLoader {

    private final PlatformCategoryRepository platformCategoryRepository;
    private final ServiceTypeSuggestionRepository suggestionRepository;
    private final UserRepository userRepository;

    /** The batch's live request rows and recipient accounts, keyed for {@link Snapshot#target}/{@link Snapshot#recipient}. */
    public record Snapshot(Map<String, RequestDecisionTarget> targets, Map<UUID, User> recipients) {

        public static final Snapshot EMPTY = new Snapshot(Map.of(), Map.of());

        @Nullable
        public RequestDecisionTarget target(RequestDecisionEvent event) {
            return targets.get(key(event.kind(), event.requestId()));
        }

        @Nullable
        public User recipient(RequestDecisionEvent event) {
            return recipients.get(event.recipientUserId());
        }
    }

    @Transactional(readOnly = true)
    public Snapshot load(Collection<RequestDecisionEvent> events) {
        if (events.isEmpty()) {
            return Snapshot.EMPTY;
        }
        Map<String, RequestDecisionTarget> targets = new HashMap<>();
        targets.putAll(loadCategories(idsOf(events, RequestKind.CATEGORY_REQUEST, RequestDecisionLoader::parseLong)));
        targets.putAll(loadSuggestions(idsOf(events, RequestKind.SERVICE_TYPE_SUGGESTION, RequestDecisionLoader::parseUuid)));
        Set<UUID> recipientIds = events.stream().map(RequestDecisionEvent::recipientUserId).collect(Collectors.toSet());
        Map<UUID, User> recipients = userRepository.findAllById(recipientIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        return new Snapshot(Map.copyOf(targets), Map.copyOf(recipients));
    }

    private Map<String, RequestDecisionTarget> loadCategories(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return platformCategoryRepository.findAllById(ids).stream().collect(Collectors.toMap(
                c -> key(RequestKind.CATEGORY_REQUEST, String.valueOf(c.getId())),
                RequestDecisionLoader::categoryTarget));
    }

    private Map<String, RequestDecisionTarget> loadSuggestions(List<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return suggestionRepository.findAllById(ids).stream().collect(Collectors.toMap(
                s -> key(RequestKind.SERVICE_TYPE_SUGGESTION, s.getId().toString()),
                RequestDecisionLoader::suggestionTarget));
    }

    private static RequestDecisionTarget categoryTarget(PlatformCategory category) {
        String name = category.getDisplayName() != null ? category.getDisplayName() : category.getName();
        return new RequestDecisionTarget(name, category.getDecisionReason(), category.getRequestedByUserId());
    }

    private static RequestDecisionTarget suggestionTarget(ServiceTypeSuggestion suggestion) {
        return new RequestDecisionTarget(suggestion.getSuggestedName(), suggestion.getDecisionReason(),
                suggestion.getRequestedByUserId());
    }

    /** The distinct parseable ids of one kind; an unparseable id loads nothing and becomes an "entity gone" skip. */
    private static <T> List<T> idsOf(Collection<RequestDecisionEvent> events, RequestKind kind,
                                     Function<String, T> parser) {
        return events.stream()
                .filter(e -> e.kind() == kind)
                .map(e -> parser.apply(e.requestId()))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    @Nullable
    private static Long parseLong(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Nullable
    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String key(RequestKind kind, String requestId) {
        return kind.name() + ':' + requestId;
    }
}
