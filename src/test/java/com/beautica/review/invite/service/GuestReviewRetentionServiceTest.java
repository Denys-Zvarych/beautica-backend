package com.beautica.review.invite.service;

import com.beautica.booking.retention.GuestPii;
import com.beautica.common.security.PhoneHasher;
import com.beautica.config.PhoneHashPepperConfig;
import com.beautica.review.event.GuestReviewsAnonymisedEvent;
import com.beautica.review.invite.repository.ReviewInviteRepository;
import com.beautica.review.repository.GuestReviewRef;
import com.beautica.review.repository.ReviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GuestReviewRetentionService — phase 402 D1/D3")
class GuestReviewRetentionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-29T03:27:00Z");
    private static final Instant CUTOFF = Instant.parse("2026-09-29T03:27:00Z");
    private static final PhoneHasher PHONE_HASHER =
            new PhoneHasher(new PhoneHashPepperConfig("unit-test-phone-hash-pepper-min-32-chars!"));

    @Mock private ReviewRepository reviewRepository;
    @Mock private ReviewInviteRepository reviewInviteRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private PlatformTransactionManager transactionManager;

    private GuestReviewRetentionService service;

    @BeforeEach
    void setUp() {
        service = new GuestReviewRetentionService(
                reviewRepository, reviewInviteRepository, eventPublisher, PHONE_HASHER, transactionManager);
    }

    @Test
    @DisplayName("D1: guest reviews of the bookings found → labels set to «Гість» and ONE eviction event with their review / master ids")
    void should_anonymiseAndPublishEvictionEvent_when_guestReviewsFound() {
        UUID booking = UUID.randomUUID();
        UUID review = UUID.randomUUID();
        UUID master = UUID.randomUUID();
        when(reviewRepository.findGuestReviewsToAnonymise(List.of(booking), GuestPii.ANONYMISED_NAME))
                .thenReturn(List.of(ref(review, master, null)));
        when(reviewRepository.anonymiseGuestDisplayNames(Set.of(review), GuestPii.ANONYMISED_NAME)).thenReturn(1);

        int anonymised = service.anonymiseReviewAuthors(List.of(booking));

        assertThat(anonymised).isEqualTo(1);
        ArgumentCaptor<GuestReviewsAnonymisedEvent> event = ArgumentCaptor.forClass(GuestReviewsAnonymisedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().reviewIds()).containsExactly(review);
        assertThat(event.getValue().masterIds()).containsExactly(master);
        assertThat(event.getValue().salonIds()).isEmpty();
    }

    @Test
    @DisplayName("D1: no un-anonymised guest review among the bookings → no UPDATE, no event")
    void should_notUpdateOrPublish_when_noGuestReviewToAnonymise() {
        UUID booking = UUID.randomUUID();
        when(reviewRepository.findGuestReviewsToAnonymise(List.of(booking), GuestPii.ANONYMISED_NAME))
                .thenReturn(List.of());

        int anonymised = service.anonymiseReviewAuthors(List.of(booking));

        assertThat(anonymised).isZero();
        verify(reviewRepository, never()).anonymiseGuestDisplayNames(anyCollection(), anyString());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("D1: empty booking-id list → no query at all (never binds IN ())")
    void should_touchNothing_when_noBookingIds() {
        int anonymised = service.anonymiseReviewAuthors(List.of());

        assertThat(anonymised).isZero();
        verifyNoInteractions(reviewRepository, eventPublisher);
    }

    @Test
    @DisplayName("D3: visit ids and single-booking ids → both skip statements run, counts summed")
    void should_runBothSkipArms_when_bothIdListsNonEmpty() {
        UUID visit = UUID.randomUUID();
        UUID booking = UUID.randomUUID();
        when(reviewInviteRepository.skipScheduledForAppointments(List.of(visit))).thenReturn(1);
        when(reviewInviteRepository.skipScheduledForBookings(List.of(booking))).thenReturn(1);

        int skipped = service.skipScheduledInvites(List.of(visit), List.of(booking));

        assertThat(skipped).isEqualTo(2);
    }

    @Test
    @DisplayName("D3: empty visit-id list → only the booking-keyed skip runs (never binds IN ())")
    void should_neverBindEmptyInList_when_oneIdListEmpty() {
        UUID booking = UUID.randomUUID();

        service.skipScheduledInvites(List.of(), List.of(booking));

        verify(reviewInviteRepository, never()).skipScheduledForAppointments(anyCollection());
        verify(reviewInviteRepository).skipScheduledForBookings(List.of(booking));
    }

    @Test
    @DisplayName("D3 purge: full batches repeat until a short batch, all at now − 30 d")
    void should_purgeInBatchesUntilShortBatch_when_backlogExceedsBatch() {
        stubTransactions();
        when(reviewInviteRepository.purgeTerminalBefore(CUTOFF, 2)).thenReturn(2, 2, 1);

        int purged = service.purgeInvites(NOW, 2, 10);

        assertThat(purged).isEqualTo(5);
        verify(reviewInviteRepository, times(3)).purgeTerminalBefore(CUTOFF, 2);
    }

    @Test
    @DisplayName("D3 purge: every batch full → stops at the per-run batch budget")
    void should_stopAtBatchBudget_when_everyBatchIsFull() {
        stubTransactions();
        when(reviewInviteRepository.purgeTerminalBefore(any(), anyInt())).thenReturn(2);

        int purged = service.purgeInvites(NOW, 2, 3);

        assertThat(purged).isEqualTo(6);
        verify(reviewInviteRepository, times(3)).purgeTerminalBefore(CUTOFF, 2);
    }

    @Test
    @DisplayName("audit-fix: erase expires the SENT invites of the phone's PhoneHasher hash at now")
    void should_expireSentInvitesByPhoneHash_when_phoneErased() {
        byte[] hash = PHONE_HASHER.hash("+380501234567").hash();
        when(reviewInviteRepository.expireSentForPhoneHash(hash, NOW)).thenReturn(2);

        int expired = service.expireSentInvitesOfPhone("+380501234567", NOW);

        assertThat(expired).isEqualTo(2);
    }

    private void stubTransactions() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    private static GuestReviewRef ref(UUID id, UUID masterId, UUID salonId) {
        return new GuestReviewRef() {
            @Override
            public UUID getId() {
                return id;
            }

            @Override
            public UUID getMasterId() {
                return masterId;
            }

            @Override
            public UUID getSalonId() {
                return salonId;
            }
        };
    }
}
