package com.beautica.notification.telegram.link;

import com.beautica.booking.domain.CancelTokenHasher;
import com.beautica.booking.dto.GuestCancelTokenTarget;
import com.beautica.booking.enums.BookingSource;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.service.GuestCancelTokenResolver;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.exception.TelegramLinkRateLimitedException;
import com.beautica.config.TelegramMode;
import com.beautica.config.TelegramProperties;
import com.beautica.notification.telegram.link.dto.GuestTelegramLinkResponse;
import com.beautica.notification.telegram.link.dto.GuestTelegramStatusResponse;
import com.beautica.notification.telegram.link.dto.TelegramAvailability;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.github.bucket4j.BandwidthBuilder;
import io.github.bucket4j.Bucket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 407 — {@link GuestTelegramLinkService}: D2 eligibility (incl. the STAFF branch that only phase 412 can make
 * reachable in the database), D3 responses, D4 {@code offerChoice}, D5 per-token budget, D6 availability, flag off.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GuestTelegramLinkService (phase 407)")
class GuestTelegramLinkServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");
    private static final String PHONE = "+380501234567";
    private static final UUID BOOKING_ID = UUID.fromString("5f0c3c1e-0000-4000-8000-000000000407");
    private static final String TOKEN = "00000000-0000-4000-8000-000000000001";
    private static final OffsetDateTime FUTURE = OffsetDateTime.parse("2026-11-02T12:00:00Z");
    private static final OffsetDateTime PAST = OffsetDateTime.parse("2026-10-29T12:00:00Z");
    private static final IssuedLink LINK =
            new IssuedLink("https://t.me/beautica_test_bot?start=" + "A".repeat(43), NOW.plus(Duration.ofMinutes(30)));

    @Mock private GuestCancelTokenResolver resolver;
    @Mock private TelegramLinkService linkService;
    @Mock private PlatformTransactionManager transactionManager;

    private GuestTelegramLinkService service;

    @BeforeEach
    void setUp() {
        service = serviceWith(properties(true, "beautica_test_bot"));
    }

    private GuestTelegramLinkService serviceWith(TelegramProperties properties) {
        return new GuestTelegramLinkService(resolver, linkService, properties, tokenBuckets(), transactionManager,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static TelegramProperties properties(boolean enabled, String botUsername) {
        return new TelegramProperties(enabled, TelegramMode.CAPTURE, "", botUsername, "",
                "https://api.telegram.org", Duration.ofMinutes(30), List.of());
    }

    private static LoadingCache<String, Bucket> tokenBuckets() {
        return Caffeine.newBuilder().build(key -> Bucket.builder()
                .addLimit(BandwidthBuilder.builder().capacity(3).refillIntervally(3, Duration.ofMinutes(10)).build())
                .build());
    }

    private static String hash(String token) {
        return CancelTokenHasher.hash(UUID.fromString(token));
    }

    private void target(BookingSource source, BookingStatus status, String phone, OffsetDateTime startsAt) {
        when(resolver.resolveByHash(hash(TOKEN)))
                .thenReturn(Optional.of(new GuestCancelTokenTarget(BOOKING_ID, source, status, phone, startsAt)));
    }

    @Nested
    @DisplayName("link-token")
    class LinkToken {

        @Test
        @DisplayName("should issue deep link when link booking confirmed in future")
        void should_issueDeepLink_when_linkBookingConfirmedInFuture() {
            target(BookingSource.LINK, BookingStatus.CONFIRMED, PHONE, FUTURE);
            when(linkService.statusForPhone(PHONE)).thenReturn(LinkStatus.NOT_LINKED);
            when(linkService.issueForBooking(BOOKING_ID, PHONE)).thenReturn(LINK);

            GuestTelegramLinkResponse response = service.issueLinkToken(TOKEN);

            assertThat(response).isEqualTo(new GuestTelegramLinkResponse(LINK.url(), LINK.expiresAt(), false));
        }

        @Test
        @DisplayName("should issue deep link when STAFF walk in has phone")
        void should_issueDeepLink_when_staffWalkInHasPhone() {
            target(BookingSource.STAFF, BookingStatus.CONFIRMED, PHONE, FUTURE);
            when(linkService.statusForPhone(PHONE)).thenReturn(LinkStatus.BLOCKED);
            when(linkService.issueForBooking(BOOKING_ID, PHONE)).thenReturn(LINK);

            GuestTelegramLinkResponse response = service.issueLinkToken(TOKEN);

            assertThat(response.alreadyLinked()).isFalse();
            assertThat(response.url()).isEqualTo(LINK.url());
        }

        @Test
        @DisplayName("should run lookup and active check in one read only transaction when issuing")
        void should_runLookupAndActiveCheckInOneReadOnlyTransaction_when_issuing() {
            target(BookingSource.LINK, BookingStatus.CONFIRMED, PHONE, FUTURE);
            when(linkService.statusForPhone(PHONE)).thenReturn(LinkStatus.NOT_LINKED);
            when(linkService.issueForBooking(BOOKING_ID, PHONE)).thenReturn(LINK);

            service.issueLinkToken(TOKEN);

            verify(transactionManager, times(1)).getTransaction(any());
            verify(transactionManager).getTransaction(argThat(TransactionDefinition::isReadOnly));
        }

        @Test
        @DisplayName("should return already linked without issuing when phone active")
        void should_returnAlreadyLinkedWithoutIssuing_when_phoneActive() {
            target(BookingSource.LINK, BookingStatus.CONFIRMED, PHONE, FUTURE);
            when(linkService.statusForPhone(PHONE)).thenReturn(LinkStatus.ACTIVE);

            GuestTelegramLinkResponse response = service.issueLinkToken(TOKEN);

            assertThat(response).isEqualTo(new GuestTelegramLinkResponse(null, null, true));
            verify(linkService, never()).issueForBooking(any(), any());
        }

        @Test
        @DisplayName("should throw not found when APP booking")
        void should_throwNotFound_when_appBooking() {
            target(BookingSource.APP, BookingStatus.CONFIRMED, null, FUTURE);

            assertThatThrownBy(() -> service.issueLinkToken(TOKEN)).isInstanceOf(NotFoundException.class);

            verify(linkService, never()).issueForBooking(any(), any());
        }

        @Test
        @DisplayName("should throw not found when STAFF without phone")
        void should_throwNotFound_when_staffWithoutPhone() {
            target(BookingSource.STAFF, BookingStatus.CONFIRMED, null, FUTURE);

            assertThatThrownBy(() -> service.issueLinkToken(TOKEN)).isInstanceOf(NotFoundException.class);
        }

        @Test
        @DisplayName("should throw not found when phone anonymised to blank")
        void should_throwNotFound_when_phoneAnonymisedToBlank() {
            target(BookingSource.LINK, BookingStatus.CONFIRMED, " ", FUTURE);

            assertThatThrownBy(() -> service.issueLinkToken(TOKEN)).isInstanceOf(NotFoundException.class);
        }

        @Test
        @DisplayName("should throw not found when booking cancelled")
        void should_throwNotFound_when_bookingCancelled() {
            target(BookingSource.LINK, BookingStatus.CANCELLED, PHONE, FUTURE);

            assertThatThrownBy(() -> service.issueLinkToken(TOKEN)).isInstanceOf(NotFoundException.class);

            verifyNoInteractions(linkService);
        }

        @Test
        @DisplayName("should throw not found when booking already started")
        void should_throwNotFound_when_bookingAlreadyStarted() {
            target(BookingSource.LINK, BookingStatus.CONFIRMED, PHONE, PAST);

            assertThatThrownBy(() -> service.issueLinkToken(TOKEN)).isInstanceOf(NotFoundException.class);

            verifyNoInteractions(linkService);
        }

        @Test
        @DisplayName("should throw not found when token unknown")
        void should_throwNotFound_when_tokenUnknown() {
            when(resolver.resolveByHash(hash(TOKEN))).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.issueLinkToken(TOKEN)).isInstanceOf(NotFoundException.class);
        }

        @Test
        @DisplayName("should throw rate limited when fourth call for same token")
        void should_throwRateLimited_when_fourthCallForSameToken() {
            when(resolver.resolveByHash(hash(TOKEN))).thenReturn(Optional.empty());
            for (int i = 0; i < 3; i++) {
                assertThatThrownBy(() -> service.issueLinkToken(TOKEN)).isInstanceOf(NotFoundException.class);
            }

            assertThatThrownBy(() -> service.issueLinkToken(TOKEN))
                    .isInstanceOfSatisfying(TelegramLinkRateLimitedException.class,
                            ex -> assertThat(ex.getRetryAfterSeconds()).isBetween(1L, 600L));

            verify(resolver, times(3)).resolveByHash(any());
        }

        @Test
        @DisplayName("should count uppercase token against same budget when case differs")
        void should_countUppercaseTokenAgainstSameBudget_when_caseDiffers() {
            String lowerCase = "00000000-0000-4000-8000-00000000000a";
            when(resolver.resolveByHash(hash(lowerCase))).thenReturn(Optional.empty());
            for (int i = 0; i < 3; i++) {
                assertThatThrownBy(() -> service.issueLinkToken(lowerCase)).isInstanceOf(NotFoundException.class);
            }

            assertThatThrownBy(() -> service.issueLinkToken(lowerCase.toUpperCase()))
                    .isInstanceOf(TelegramLinkRateLimitedException.class);
        }
    }

    @Nested
    @DisplayName("status")
    class Status {

        @Test
        @DisplayName("should not offer choice when link booking not linked")
        void should_notOfferChoice_when_linkBookingNotLinked() {
            target(BookingSource.LINK, BookingStatus.CONFIRMED, PHONE, FUTURE);
            when(linkService.statusForPhone(PHONE)).thenReturn(LinkStatus.NOT_LINKED);

            assertThat(service.status(TOKEN)).isEqualTo(new GuestTelegramStatusResponse(true, false, false));
        }

        @Test
        @DisplayName("should offer choice when STAFF walk in not linked")
        void should_offerChoice_when_staffWalkInNotLinked() {
            target(BookingSource.STAFF, BookingStatus.CONFIRMED, PHONE, FUTURE);
            when(linkService.statusForPhone(PHONE)).thenReturn(LinkStatus.NOT_LINKED);

            assertThat(service.status(TOKEN)).isEqualTo(new GuestTelegramStatusResponse(true, false, true));
        }

        @Test
        @DisplayName("should report linked without choice when STAFF walk in linked")
        void should_reportLinkedWithoutChoice_when_staffWalkInLinked() {
            target(BookingSource.STAFF, BookingStatus.CONFIRMED, PHONE, FUTURE);
            when(linkService.statusForPhone(PHONE)).thenReturn(LinkStatus.ACTIVE);

            assertThat(service.status(TOKEN)).isEqualTo(new GuestTelegramStatusResponse(true, true, false));
        }

        @Test
        @DisplayName("should report not linked and offer choice when STAFF link blocked")
        void should_reportNotLinkedAndOfferChoice_when_staffLinkBlocked() {
            target(BookingSource.STAFF, BookingStatus.CONFIRMED, PHONE, FUTURE);
            when(linkService.statusForPhone(PHONE)).thenReturn(LinkStatus.BLOCKED);

            assertThat(service.status(TOKEN)).isEqualTo(new GuestTelegramStatusResponse(true, false, true));
        }

        @Test
        @DisplayName("should answer when booking past and cancelled")
        void should_answer_when_bookingPastAndCancelled() {
            target(BookingSource.LINK, BookingStatus.CANCELLED, PHONE, PAST);
            when(linkService.statusForPhone(PHONE)).thenReturn(LinkStatus.ACTIVE);

            assertThat(service.status(TOKEN)).isEqualTo(new GuestTelegramStatusResponse(true, true, false));
        }

        @Test
        @DisplayName("should throw not found when token unknown")
        void should_throwNotFound_when_tokenUnknown() {
            when(resolver.resolveByHash(hash(TOKEN))).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.status(TOKEN)).isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    @DisplayName("unlink")
    class Unlink {

        @Test
        @DisplayName("should delete booking phone link when eligible")
        void should_deleteBookingPhoneLink_when_eligible() {
            target(BookingSource.LINK, BookingStatus.COMPLETED, PHONE, PAST);

            service.unlink(TOKEN);

            verify(linkService).unlinkPhone(PHONE);
        }

        @Test
        @DisplayName("should throw not found when token unknown")
        void should_throwNotFound_when_tokenUnknown() {
            when(resolver.resolveByHash(hash(TOKEN))).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.unlink(TOKEN)).isInstanceOf(NotFoundException.class);

            verifyNoInteractions(linkService);
        }
    }

    @Nested
    @DisplayName("channel unavailable")
    class Unavailable {

        @Test
        @DisplayName("should throw not found on all three without lookup when flag off")
        void should_throwNotFoundOnAllThreeWithoutLookup_when_flagOff() {
            GuestTelegramLinkService off = serviceWith(properties(false, "beautica_test_bot"));

            assertThatThrownBy(() -> off.issueLinkToken(TOKEN)).isInstanceOf(NotFoundException.class);
            assertThatThrownBy(() -> off.status(TOKEN)).isInstanceOf(NotFoundException.class);
            assertThatThrownBy(() -> off.unlink(TOKEN)).isInstanceOf(NotFoundException.class);

            verifyNoInteractions(resolver, linkService);
        }

        @Test
        @DisplayName("should unlink when flag on but bot username blank")
        void should_unlink_when_flagOnButBotUsernameBlank() {
            GuestTelegramLinkService noUsername = serviceWith(properties(true, ""));
            target(BookingSource.LINK, BookingStatus.CONFIRMED, PHONE, FUTURE);

            noUsername.unlink(TOKEN);

            verify(linkService).unlinkPhone(PHONE);
        }

        @Test
        @DisplayName("should throw not found without issuing when bot username blank")
        void should_throwNotFoundWithoutIssuing_when_botUsernameBlank() {
            GuestTelegramLinkService noUsername = serviceWith(properties(true, ""));

            assertThatThrownBy(() -> noUsername.issueLinkToken(TOKEN)).isInstanceOf(NotFoundException.class);

            verifyNoInteractions(resolver, linkService);
        }
    }

    @Nested
    @DisplayName("booking-response availability (D6)")
    class Availability {

        @Test
        @DisplayName("should return unavailable without query when flag off")
        void should_returnUnavailableWithoutQuery_when_flagOff() {
            TelegramAvailability availability =
                    serviceWith(properties(false, "beautica_test_bot")).availabilityForPhone(PHONE);

            assertThat(availability).isEqualTo(new TelegramAvailability(false, false));
            verifyNoInteractions(linkService);
        }

        @Test
        @DisplayName("should report linked when phone active")
        void should_reportLinked_when_phoneActive() {
            when(linkService.statusForPhone(PHONE)).thenReturn(LinkStatus.ACTIVE);

            assertThat(service.availabilityForPhone(PHONE)).isEqualTo(new TelegramAvailability(true, true));
        }

        @Test
        @DisplayName("should degrade to unavailable when link read fails")
        void should_degradeToUnavailable_when_linkReadFails() {
            when(linkService.statusForPhone(PHONE)).thenThrow(new DataAccessResourceFailureException("db down"));

            assertThat(service.availabilityForPhone(PHONE)).isEqualTo(TelegramAvailability.UNAVAILABLE);
        }

        @Test
        @DisplayName("should return unavailable when status tx cannot be created")
        void should_returnUnavailable_when_statusTxCannotBeCreated() {
            when(linkService.statusForPhone(PHONE)).thenThrow(new org.springframework.transaction
                    .CannotCreateTransactionException("Could not open JPA EntityManager for transaction"));

            assertThat(service.availabilityForPhone(PHONE)).isEqualTo(TelegramAvailability.UNAVAILABLE);
        }

        @Test
        @DisplayName("should report not linked when phone blocked")
        void should_reportNotLinked_when_phoneBlocked() {
            when(linkService.statusForPhone(PHONE)).thenReturn(LinkStatus.BLOCKED);

            assertThat(service.availabilityForPhone(PHONE)).isEqualTo(new TelegramAvailability(true, false));
        }
    }
}
