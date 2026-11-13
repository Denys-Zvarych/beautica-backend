package com.beautica.notification.service;

import com.beautica.service.entity.PlatformCategory;
import com.beautica.service.entity.ServiceTypeSuggestion;
import com.beautica.service.repository.PlatformCategoryRepository;
import com.beautica.service.repository.ServiceTypeSuggestionRepository;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 491 — {@link RequestDecisionLoader}: the drain's batch pre-load of decided requests and recipients. The
 * IT drives it end to end; this pins the mapping rules the IT's fixtures cannot reach (display-name fallback,
 * unparseable ids, kind-scoped keys, no query for an absent kind).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RequestDecisionLoader — REQUEST_DECIDED pre-load snapshot")
class RequestDecisionLoaderTest {

    @Mock private PlatformCategoryRepository platformCategoryRepository;
    @Mock private ServiceTypeSuggestionRepository suggestionRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks private RequestDecisionLoader loader;

    private UUID recipientId;
    private User recipient;

    @BeforeEach
    void setUp() {
        recipientId = UUID.randomUUID();
        recipient = mock(User.class);
        lenient().when(recipient.getId()).thenReturn(recipientId);
    }

    @Test
    @DisplayName("an empty batch returns the EMPTY snapshot without a single query")
    void should_returnEmptySnapshot_when_noEvents() {
        RequestDecisionLoader.Snapshot snapshot = loader.load(List.of());

        assertThat(snapshot).isSameAs(RequestDecisionLoader.Snapshot.EMPTY);
        verifyNoInteractions(platformCategoryRepository, suggestionRepository, userRepository);
    }

    @Test
    @DisplayName("a category target carries the display name, stored reason and current requester")
    void should_mapCategoryTarget_when_categoryHasDisplayName() {
        PlatformCategory category = category(42L, "NAIL_ART", "Нейл-арт", "дублікат", recipientId);
        when(platformCategoryRepository.findAllById(List.of(42L))).thenReturn(List.of(category));
        when(userRepository.findAllById(any())).thenReturn(List.of(recipient));
        RequestDecisionEvent event = new RequestDecisionEvent(RequestKind.CATEGORY_REQUEST, "42", false, recipientId);

        RequestDecisionLoader.Snapshot snapshot = loader.load(List.of(event));

        assertThat(snapshot.target(event)).isEqualTo(new RequestDecisionTarget("Нейл-арт", "дублікат", recipientId));
        assertThat(snapshot.recipient(event)).isSameAs(recipient);
        verify(suggestionRepository, never()).findAllById(any());
    }

    @Test
    @DisplayName("a category without a display name falls back to its code name")
    void should_fallBackToName_when_categoryDisplayNameNull() {
        PlatformCategory category = category(7L, "BROWS", null, null, recipientId);
        when(platformCategoryRepository.findAllById(List.of(7L))).thenReturn(List.of(category));
        when(userRepository.findAllById(any())).thenReturn(List.of(recipient));
        RequestDecisionEvent event = new RequestDecisionEvent(RequestKind.CATEGORY_REQUEST, "7", true, recipientId);

        assertThat(loader.load(List.of(event)).target(event))
                .isEqualTo(new RequestDecisionTarget("BROWS", null, recipientId));
    }

    @Test
    @DisplayName("a suggestion target is keyed by kind: the same id text never resolves for the other kind")
    void should_keyTargetsByKind_when_suggestionLoaded() {
        UUID suggestionId = UUID.randomUUID();
        ServiceTypeSuggestion suggestion = mock(ServiceTypeSuggestion.class);
        when(suggestion.getId()).thenReturn(suggestionId);
        when(suggestion.getSuggestedName()).thenReturn("Балаяж");
        when(suggestion.getDecisionReason()).thenReturn(null);
        when(suggestion.getRequestedByUserId()).thenReturn(recipientId);
        when(suggestionRepository.findAllById(List.of(suggestionId))).thenReturn(List.of(suggestion));
        when(userRepository.findAllById(any())).thenReturn(List.of(recipient));
        RequestDecisionEvent event = new RequestDecisionEvent(
                RequestKind.SERVICE_TYPE_SUGGESTION, suggestionId.toString(), true, recipientId);

        RequestDecisionLoader.Snapshot snapshot = loader.load(List.of(event));

        assertThat(snapshot.target(event)).isEqualTo(new RequestDecisionTarget("Балаяж", null, recipientId));
        assertThat(snapshot.target(new RequestDecisionEvent(
                RequestKind.CATEGORY_REQUEST, suggestionId.toString(), true, recipientId))).isNull();
        verify(platformCategoryRepository, never()).findAllById(any());
    }

    @Test
    @DisplayName("an unparseable request id loads nothing (later an 'entity gone' skip) instead of throwing")
    void should_resolveNoTarget_when_requestIdUnparseable() {
        when(userRepository.findAllById(any())).thenReturn(List.of(recipient));
        RequestDecisionEvent badCategory = new RequestDecisionEvent(RequestKind.CATEGORY_REQUEST, "not-a-long", false, recipientId);
        RequestDecisionEvent badSuggestion = new RequestDecisionEvent(RequestKind.SERVICE_TYPE_SUGGESTION, "not-a-uuid", false, recipientId);

        RequestDecisionLoader.Snapshot snapshot = loader.load(List.of(badCategory, badSuggestion));

        assertThat(snapshot.target(badCategory)).isNull();
        assertThat(snapshot.target(badSuggestion)).isNull();
        verify(platformCategoryRepository, never()).findAllById(any());
        verify(suggestionRepository, never()).findAllById(any());
    }

    @Test
    @DisplayName("a recipient missing from users resolves to null (later a 'recipient gone' skip)")
    void should_resolveNoRecipient_when_userGone() {
        PlatformCategory category = category(42L, "NAIL_ART", "Нейл-арт", null, recipientId);
        when(platformCategoryRepository.findAllById(List.of(42L))).thenReturn(List.of(category));
        when(userRepository.findAllById(any())).thenReturn(List.of());
        RequestDecisionEvent event = new RequestDecisionEvent(RequestKind.CATEGORY_REQUEST, "42", true, recipientId);

        assertThat(loader.load(List.of(event)).recipient(event)).isNull();
    }

    private static PlatformCategory category(long id, String name, String displayName, String reason, UUID requester) {
        PlatformCategory category = mock(PlatformCategory.class);
        when(category.getId()).thenReturn(id);
        when(category.getDisplayName()).thenReturn(displayName);
        if (displayName == null) {
            when(category.getName()).thenReturn(name);
        }
        when(category.getDecisionReason()).thenReturn(reason);
        when(category.getRequestedByUserId()).thenReturn(requester);
        return category;
    }
}
