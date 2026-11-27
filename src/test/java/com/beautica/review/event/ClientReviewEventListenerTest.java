package com.beautica.review.event;

import com.beautica.review.repository.ClientReviewRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** B570 (REV-45) — {@link ClientReviewEventListener#onClientReviewDeleted}: recalculate a registered subject only. */
@ExtendWith(MockitoExtension.class)
@DisplayName("ClientReviewEventListener — delete event (B570)")
class ClientReviewEventListenerTest {

    @Mock private ClientReviewRepository clientReviewRepository;
    @InjectMocks private ClientReviewEventListener listener;

    @Test
    @DisplayName("a delete for a registered client recalculates that client's aggregate")
    void should_recalculate_when_deletedReviewSubjectIsRegistered() {
        UUID clientId = UUID.randomUUID();

        listener.onClientReviewDeleted(new ClientReviewDeletedEvent(clientId));

        verify(clientReviewRepository).recalculateClientRating(clientId);
    }

    @Test
    @DisplayName("a delete for a guest (null client) makes no recalculation call")
    void should_notRecalculate_when_deletedReviewSubjectIsGuest() {
        listener.onClientReviewDeleted(new ClientReviewDeletedEvent(null));

        verifyNoInteractions(clientReviewRepository);
    }

    @Test
    @DisplayName("a failing recalculation is logged, never thrown (the delete already committed)")
    void should_swallowFailure_when_recalculationThrows() {
        UUID clientId = UUID.randomUUID();
        doThrow(new IllegalStateException("db down")).when(clientReviewRepository).recalculateClientRating(clientId);

        assertThatCode(() -> listener.onClientReviewDeleted(new ClientReviewDeletedEvent(clientId)))
                .doesNotThrowAnyException();
    }
}
