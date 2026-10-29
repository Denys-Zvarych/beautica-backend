package com.beautica.review.invite.service;

import com.beautica.booking.entity.Booking;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.master.entity.Master;
import com.beautica.review.invite.dto.GuestReviewItemRequest;
import com.beautica.review.invite.dto.ReviewInviteResponse;
import com.beautica.review.invite.entity.ReviewInvite;
import com.beautica.review.invite.entity.ReviewInviteStatus;
import com.beautica.review.invite.repository.ReviewInviteRepository;
import com.beautica.review.invite.repository.SmsOptOutRepository;
import com.beautica.review.invite.support.ReviewInviteTestProperties;
import com.beautica.review.service.GuestLinkReviewDraft;
import com.beautica.review.service.ReviewService;
import com.beautica.salon.entity.Salon;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("GuestReviewService — phase 401")
class GuestReviewServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");
    private static final String TOKEN = "AbCdEfGhIjKlMnOpQrStUv";
    private static final byte[] TOKEN_HASH = ReviewInviteTokenHasher.hash(TOKEN);
    private static final byte[] PHONE_HASH = new byte[32];
    private static final UUID INVITE_ID = UUID.randomUUID();
    private static final UUID APPOINTMENT_ID = UUID.randomUUID();
    private static final UUID SINGLE_BOOKING_ID = UUID.randomUUID();

    @Mock private ReviewInviteRepository inviteRepository;
    @Mock private SmsOptOutRepository optOutRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private ReviewService reviewService;

    private GuestReviewService service;

    @BeforeEach
    void setUp() {
        service = service(true);
    }

    private GuestReviewService service(boolean enabled) {
        return new GuestReviewService(inviteRepository, optOutRepository, bookingRepository, reviewService,
                enabled ? ReviewInviteTestProperties.enabled() : ReviewInviteTestProperties.disabled(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ReviewInvite visitInvite() {
        ReviewInvite invite = mock(ReviewInvite.class);
        when(invite.getId()).thenReturn(INVITE_ID);
        when(invite.getStatus()).thenReturn(ReviewInviteStatus.SENT);
        when(invite.isBookingKeyed()).thenReturn(false);
        when(invite.getAppointmentId()).thenReturn(APPOINTMENT_ID);
        when(invite.getPhoneHash()).thenReturn(PHONE_HASH.clone());
        return invite;
    }

    private static ReviewInvite bookingInvite() {
        ReviewInvite invite = mock(ReviewInvite.class);
        when(invite.getId()).thenReturn(INVITE_ID);
        when(invite.getStatus()).thenReturn(ReviewInviteStatus.SENT);
        when(invite.isBookingKeyed()).thenReturn(true);
        when(invite.getBookingId()).thenReturn(SINGLE_BOOKING_ID);
        when(invite.getPhoneHash()).thenReturn(PHONE_HASH.clone());
        return invite;
    }

    private static Booking booking(UUID id, String service, String guestName, String guestSurname, Salon salon,
                                   OffsetDateTime startsAt) {
        Master master = mock(Master.class);
        when(master.displayFirstName()).thenReturn("Оксана");
        when(master.displayLastName()).thenReturn("Коваленко");
        ServiceDefinition definition = mock(ServiceDefinition.class);
        when(definition.getName()).thenReturn(service);
        MasterServiceAssignment assignment = mock(MasterServiceAssignment.class);
        when(assignment.getServiceDefinition()).thenReturn(definition);
        Booking booking = mock(Booking.class);
        when(booking.getId()).thenReturn(id);
        when(booking.getMaster()).thenReturn(master);
        when(booking.getSalon()).thenReturn(salon);
        when(booking.getMasterService()).thenReturn(assignment);
        when(booking.getStartsAt()).thenReturn(startsAt);
        when(booking.getGuestName()).thenReturn(guestName);
        when(booking.getGuestSurname()).thenReturn(guestSurname);
        return booking;
    }

    private static Booking booking(UUID id) {
        return booking(id, "Манікюр", "Олена", "Кравець", null, OffsetDateTime.parse("2026-10-23T22:30:00Z"));
    }

    private static GuestReviewItemRequest item(UUID bookingId, int rating) {
        return new GuestReviewItemRequest(bookingId, rating, null);
    }

    // ── resolveInviteBookings (D8) ─────────────────────────────────────────────

    @Test
    @DisplayName("should_readVisitBookings_when_inviteIsVisitKeyed")
    void should_readVisitBookings_when_inviteIsVisitKeyed() {
        List<Booking> visit = List.of(booking(UUID.randomUUID()), booking(UUID.randomUUID()));
        when(bookingRepository.findGuestReviewableByAppointmentIdWithGraph(APPOINTMENT_ID)).thenReturn(visit);

        List<Booking> resolved = service.resolveInviteBookings(visitInvite());

        assertThat(resolved).isEqualTo(visit);
        verify(bookingRepository, never()).findGuestReviewableSingleBookingWithGraph(any());
    }

    @Test
    @DisplayName("should_readOneBooking_when_inviteIsBookingKeyed")
    void should_readOneBooking_when_inviteIsBookingKeyed() {
        Booking single = booking(SINGLE_BOOKING_ID);
        when(bookingRepository.findGuestReviewableSingleBookingWithGraph(SINGLE_BOOKING_ID))
                .thenReturn(Optional.of(single));

        List<Booking> resolved = service.resolveInviteBookings(bookingInvite());

        assertThat(resolved).containsExactly(single);
        verify(bookingRepository, never()).findGuestReviewableByAppointmentIdWithGraph(any());
    }

    @Test
    @DisplayName("should_resolveEmpty_when_bookingKeyedBookingIneligible")
    void should_resolveEmpty_when_bookingKeyedBookingIneligible() {
        // APP / STAFF / has a client / not COMPLETED / gained an appointment_id: the query's predicate excludes it.
        when(bookingRepository.findGuestReviewableSingleBookingWithGraph(SINGLE_BOOKING_ID))
                .thenReturn(Optional.empty());

        List<Booking> resolved = service.resolveInviteBookings(bookingInvite());

        assertThat(resolved).isEmpty();
    }

    @Test
    @DisplayName("should_throw404_when_bookingKeyedInviteResolvesEmpty")
    void should_throw404_when_bookingKeyedInviteResolvesEmpty() {
        ReviewInvite invite = bookingInvite();
        when(inviteRepository.findRedeemableOrConsumed(eq(TOKEN_HASH), eq(NOW))).thenReturn(Optional.of(invite));
        when(bookingRepository.findGuestReviewableSingleBookingWithGraph(SINGLE_BOOKING_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getInvite(TOKEN)).isInstanceOf(NotFoundException.class);
    }

    // ── GET ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_returnOpenForm_when_visitInviteRedeemable")
    void should_returnOpenForm_when_visitInviteRedeemable() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        ReviewInvite invite = visitInvite();
        when(inviteRepository.findRedeemableOrConsumed(TOKEN_HASH, NOW)).thenReturn(Optional.of(invite));
        List<Booking> visit = List.of(booking(first), booking(second, "Педикюр", "Олена", "Кравець", null,
                OffsetDateTime.parse("2026-10-24T00:00:00Z")));
        when(bookingRepository.findGuestReviewableByAppointmentIdWithGraph(APPOINTMENT_ID)).thenReturn(visit);

        ReviewInviteResponse response = service.getInvite(TOKEN);

        assertThat(response.state()).isEqualTo(ReviewInviteResponse.STATE_OPEN);
        assertThat(response.providerType()).isEqualTo("MASTER");
        assertThat(response.providerName()).isEqualTo("Оксана Коваленко");
        // 2026-10-23T22:30Z is 01:30 on the 24th in Kyiv (UTC+3 summer time ends 26 Oct).
        assertThat(response.visitDate()).isEqualTo(LocalDate.of(2026, 10, 24));
        assertThat(response.items()).extracting("bookingId").containsExactly(first, second);
        assertThat(response.items()).extracting("serviceName").containsExactly("Манікюр", "Педикюр");
        assertThat(response.displayName()).isEqualTo("Олена К.");
    }

    @Test
    @DisplayName("should_nameSalon_when_bookingBelongsToSalon")
    void should_nameSalon_when_bookingBelongsToSalon() {
        Salon salon = mock(Salon.class);
        when(salon.getName()).thenReturn("Лаванда");
        ReviewInvite invite = bookingInvite();
        when(inviteRepository.findRedeemableOrConsumed(TOKEN_HASH, NOW)).thenReturn(Optional.of(invite));
        Booking single = booking(SINGLE_BOOKING_ID, "Манікюр", "Олена", null, salon,
                OffsetDateTime.parse("2026-10-20T09:00:00Z"));
        when(bookingRepository.findGuestReviewableSingleBookingWithGraph(SINGLE_BOOKING_ID))
                .thenReturn(Optional.of(single));

        ReviewInviteResponse response = service.getInvite(TOKEN);

        assertThat(response.providerType()).isEqualTo("SALON");
        assertThat(response.providerName()).isEqualTo("Лаванда");
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).masterName()).isEqualTo("Оксана Коваленко");
    }

    // ── GET: provider card (website MasterStrip) ───────────────────────────────

    private static Master cardMaster(boolean active, User user, Salon salon, int reviewCount, String avgRating) {
        Master master = mock(Master.class);
        when(master.displayFirstName()).thenReturn("Оксана");
        when(master.displayLastName()).thenReturn("Коваленко");
        when(master.isActive()).thenReturn(active);
        when(master.getUser()).thenReturn(user);
        when(master.getSalon()).thenReturn(salon);
        when(master.getReviewCount()).thenReturn(reviewCount);
        when(master.getAvgRating()).thenReturn(new BigDecimal(avgRating));
        return master;
    }

    private static User cardUser() {
        User user = mock(User.class);
        when(user.getAvatarUrl()).thenReturn("https://cdn.beautica.app/u/a.jpg");
        when(user.getProfessionalTitle()).thenReturn("Майстер манікюру");
        return user;
    }

    private static Salon cardSalon(boolean active, int reviewCount, String avgRating) {
        Salon salon = mock(Salon.class);
        when(salon.getName()).thenReturn("Лаванда");
        when(salon.isActive()).thenReturn(active);
        when(salon.getAvatarUrl()).thenReturn("https://cdn.beautica.app/s/logo.jpg");
        when(salon.getReviewCount()).thenReturn(reviewCount);
        when(salon.getAvgRating()).thenReturn(new BigDecimal(avgRating));
        return salon;
    }

    private ReviewInviteResponse readSingle(Master master, Salon bookingSalon) {
        ReviewInvite invite = bookingInvite();
        when(inviteRepository.findRedeemableOrConsumed(TOKEN_HASH, NOW)).thenReturn(Optional.of(invite));
        Booking single = booking(SINGLE_BOOKING_ID, "Манікюр", "Олена", "Кравець", bookingSalon,
                OffsetDateTime.parse("2026-10-20T09:00:00Z"));
        when(single.getMaster()).thenReturn(master);
        when(bookingRepository.findGuestReviewableSingleBookingWithGraph(SINGLE_BOOKING_ID))
                .thenReturn(Optional.of(single));
        return service.getInvite(TOKEN);
    }

    @Test
    @DisplayName("should_returnMasterCard_when_activeMasterHasReviews")
    void should_returnMasterCard_when_activeMasterHasReviews() {
        Master master = cardMaster(true, cardUser(), null, 7, "4.57");

        ReviewInviteResponse response = readSingle(master, null);

        assertThat(response.avatarUrl()).isEqualTo("https://cdn.beautica.app/u/a.jpg");
        assertThat(response.avgRating()).isEqualByComparingTo("4.57");
        assertThat(response.reviewCount()).isEqualTo(7);
        assertThat(response.professionalTitle()).isEqualTo("Майстер манікюру");
    }

    @Test
    @DisplayName("should_returnNullRatingAndZeroCount_when_masterHasNoReviews")
    void should_returnNullRatingAndZeroCount_when_masterHasNoReviews() {
        Master master = cardMaster(true, cardUser(), null, 0, "0.00");

        ReviewInviteResponse response = readSingle(master, null);

        assertThat(response.avgRating()).isNull();
        assertThat(response.reviewCount()).isZero();
        assertThat(response.avatarUrl()).isEqualTo("https://cdn.beautica.app/u/a.jpg");
        assertThat(response.professionalTitle()).isEqualTo("Майстер манікюру");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"inactive", "detached", "salonClosed"})
    @DisplayName("should_hideMasterCard_when_masterNotPubliclyVisible")
    void should_hideMasterCard_when_masterNotPubliclyVisible(String state) {
        Master master = switch (state) {
            case "inactive" -> cardMaster(false, cardUser(), null, 7, "4.57");
            case "detached" -> cardMaster(true, null, null, 7, "4.57");
            default -> cardMaster(true, cardUser(), cardSalon(false, 3, "5.00"), 7, "4.57");
        };

        ReviewInviteResponse response = readSingle(master, null);

        assertThat(response.state()).isEqualTo(ReviewInviteResponse.STATE_OPEN);
        assertThat(response.providerName()).isEqualTo("Оксана Коваленко");
        assertThat(response.avatarUrl()).isNull();
        assertThat(response.avgRating()).isNull();
        assertThat(response.reviewCount()).isZero();
        assertThat(response.professionalTitle()).isNull();
    }

    @Test
    @DisplayName("should_returnSalonCardWithoutTitle_when_visitSalonPerformed")
    void should_returnSalonCardWithoutTitle_when_visitSalonPerformed() {
        Salon salon = cardSalon(true, 3, "4.67");
        Master master = cardMaster(true, cardUser(), salon, 9, "4.10");

        ReviewInviteResponse response = readSingle(master, salon);

        assertThat(response.providerType()).isEqualTo("SALON");
        assertThat(response.avatarUrl()).isEqualTo("https://cdn.beautica.app/s/logo.jpg");
        assertThat(response.avgRating()).isEqualByComparingTo("4.67");
        assertThat(response.reviewCount()).isEqualTo(3);
        assertThat(response.professionalTitle()).isNull();
    }

    @Test
    @DisplayName("should_hideSalonCard_when_salonClosed")
    void should_hideSalonCard_when_salonClosed() {
        Salon salon = cardSalon(false, 3, "4.67");
        Master master = cardMaster(true, cardUser(), salon, 9, "4.10");

        ReviewInviteResponse response = readSingle(master, salon);

        assertThat(response.providerName()).isEqualTo("Лаванда");
        assertThat(response.avatarUrl()).isNull();
        assertThat(response.avgRating()).isNull();
        assertThat(response.reviewCount()).isZero();
    }

    @ParameterizedTest(name = "[{index}] name=''{0}'' surname=''{1}'' -> ''{2}''")
    @CsvSource(value = {
            "Олена|Кравець|Олена К.",
            "Олена Марія|Кравець|Олена К.",
            "Олена|''|Олена",
            "Олена|123|Олена",
            "+380 50 111 22 33|Кравець|Гість",
            "(050)|''|Гість",
            "Анна-|Іванова|Анна І."
    }, delimiter = '|', emptyValue = "")
    @DisplayName("should_buildDisplayNameByFactoryRule_when_reading")
    void should_buildDisplayNameByFactoryRule_when_reading(String name, String surname, String expected) {
        ReviewInvite invite = bookingInvite();
        when(inviteRepository.findRedeemableOrConsumed(TOKEN_HASH, NOW)).thenReturn(Optional.of(invite));
        Booking single = booking(SINGLE_BOOKING_ID, "Манікюр", name, surname, null,
                OffsetDateTime.parse("2026-10-20T09:00:00Z"));
        when(bookingRepository.findGuestReviewableSingleBookingWithGraph(SINGLE_BOOKING_ID))
                .thenReturn(Optional.of(single));

        ReviewInviteResponse response = service.getInvite(TOKEN);

        assertThat(response.displayName()).isEqualTo(expected);
    }

    @Test
    @DisplayName("should_returnAlreadyReviewed_when_tokenConsumed")
    void should_returnAlreadyReviewed_when_tokenConsumed() {
        ReviewInvite consumed = bookingInvite();
        when(consumed.getStatus()).thenReturn(ReviewInviteStatus.CONSUMED);
        when(inviteRepository.findRedeemableOrConsumed(TOKEN_HASH, NOW)).thenReturn(Optional.of(consumed));

        ReviewInviteResponse response = service.getInvite(TOKEN);

        assertThat(response).isEqualTo(ReviewInviteResponse.alreadyReviewed());
        verifyNoInteractions(bookingRepository);
    }

    @Test
    @DisplayName("should_throw404_when_tokenUnknownOrExpired")
    void should_throw404_when_tokenUnknownOrExpired() {
        when(inviteRepository.findRedeemableOrConsumed(TOKEN_HASH, NOW)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getInvite(TOKEN))
                .isInstanceOf(NotFoundException.class)
                .hasMessage(GuestReviewService.NOT_FOUND_MESSAGE);
        verifyNoInteractions(bookingRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "short", "has space in it here!!", "AbCdEfGhIjKlMnOpQrSt%v", "../../../../etc/passwd"})
    @DisplayName("should_throw404WithoutDbAccess_when_tokenMalformed")
    void should_throw404WithoutDbAccess_when_tokenMalformed(String token) {
        assertThatThrownBy(() -> service.getInvite(token)).isInstanceOf(NotFoundException.class);

        verifyNoInteractions(inviteRepository, bookingRepository);
    }

    @Test
    @DisplayName("should_throw404OnEveryRoute_when_featureOff")
    void should_throw404OnEveryRoute_when_featureOff() {
        GuestReviewService off = service(false);
        List<GuestReviewItemRequest> items = List.of(item(UUID.randomUUID(), 5));

        assertThatThrownBy(() -> off.getInvite(TOKEN)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> off.submit(TOKEN, items)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> off.optOut(TOKEN)).isInstanceOf(NotFoundException.class);

        verifyNoInteractions(inviteRepository, optOutRepository, bookingRepository, reviewService);
    }

    // ── POST /reviews ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_redeemThenCreateOnlySubmittedReviews_when_subsetSubmitted")
    void should_redeemThenCreateOnlySubmittedReviews_when_subsetSubmitted() {
        Booking first = booking(UUID.randomUUID());
        Booking second = booking(UUID.randomUUID());
        ReviewInvite invite = visitInvite();
        when(inviteRepository.consumeRedeemable(TOKEN_HASH, NOW)).thenReturn(Optional.of(invite));
        when(bookingRepository.findGuestReviewableByAppointmentIdWithGraph(APPOINTMENT_ID))
                .thenReturn(List.of(first, second));

        int created = service.submit(TOKEN, List.of(new GuestReviewItemRequest(second.getId(), 4, "Добре")));

        assertThat(created).isEqualTo(1);
        InOrder order = inOrder(inviteRepository, reviewService);
        order.verify(inviteRepository).consumeRedeemable(TOKEN_HASH, NOW);
        order.verify(reviewService).createGuestLinkReviews(List.of(new GuestLinkReviewDraft(second, 4, "Добре")));
    }

    @Test
    @DisplayName("should_passAllItemsInOneCall_when_multipleItemsSubmitted")
    void should_passAllItemsInOneCall_when_multipleItemsSubmitted() {
        Booking first = booking(UUID.randomUUID());
        Booking second = booking(UUID.randomUUID());
        ReviewInvite invite = visitInvite();
        when(inviteRepository.consumeRedeemable(TOKEN_HASH, NOW)).thenReturn(Optional.of(invite));
        when(bookingRepository.findGuestReviewableByAppointmentIdWithGraph(APPOINTMENT_ID))
                .thenReturn(List.of(first, second));

        int created = service.submit(TOKEN, List.of(item(second.getId(), 3), item(first.getId(), 5)));

        assertThat(created).isEqualTo(2);
        verify(reviewService).createGuestLinkReviews(List.of(new GuestLinkReviewDraft(second, 3, null),
                new GuestLinkReviewDraft(first, 5, null)));
    }

    @Test
    @DisplayName("should_throw409WithoutReviews_when_bookingNotInInvite")
    void should_throw409WithoutReviews_when_bookingNotInInvite() {
        ReviewInvite invite = visitInvite();
        when(inviteRepository.consumeRedeemable(TOKEN_HASH, NOW)).thenReturn(Optional.of(invite));
        List<Booking> visit = List.of(booking(UUID.randomUUID()));
        when(bookingRepository.findGuestReviewableByAppointmentIdWithGraph(APPOINTMENT_ID)).thenReturn(visit);

        // The redeem already ran: the 409 rolls it back with the transaction (GuestReviewSubmitIT pins "stays SENT").
        assertThatThrownBy(() -> service.submit(TOKEN, List.of(item(UUID.randomUUID(), 5))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        verifyNoInteractions(reviewService);
    }

    @Test
    @DisplayName("should_acceptOnlyItsBooking_when_inviteBookingKeyed")
    void should_acceptOnlyItsBooking_when_inviteBookingKeyed() {
        Booking single = booking(SINGLE_BOOKING_ID);
        ReviewInvite invite = bookingInvite();
        when(inviteRepository.consumeRedeemable(TOKEN_HASH, NOW)).thenReturn(Optional.of(invite));
        when(bookingRepository.findGuestReviewableSingleBookingWithGraph(SINGLE_BOOKING_ID))
                .thenReturn(Optional.of(single));

        int created = service.submit(TOKEN, List.of(item(SINGLE_BOOKING_ID, 5)));

        assertThat(created).isEqualTo(1);
        verify(reviewService).createGuestLinkReviews(List.of(new GuestLinkReviewDraft(single, 5, null)));
        assertThatThrownBy(() -> service.submit(TOKEN, List.of(item(UUID.randomUUID(), 5))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    @DisplayName("should_throw400WithoutReviews_when_sameBookingRatedTwice")
    void should_throw400WithoutReviews_when_sameBookingRatedTwice() {
        Booking single = booking(SINGLE_BOOKING_ID);
        ReviewInvite invite = bookingInvite();
        when(inviteRepository.consumeRedeemable(TOKEN_HASH, NOW)).thenReturn(Optional.of(invite));
        when(bookingRepository.findGuestReviewableSingleBookingWithGraph(SINGLE_BOOKING_ID))
                .thenReturn(Optional.of(single));

        assertThatThrownBy(() -> service.submit(TOKEN,
                List.of(item(SINGLE_BOOKING_ID, 5), item(SINGLE_BOOKING_ID, 4))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        verifyNoInteractions(reviewService);
    }

    @Test
    @DisplayName("should_throw404_when_submitTokenNotRedeemable")
    void should_throw404_when_submitTokenNotRedeemable() {
        when(inviteRepository.consumeRedeemable(TOKEN_HASH, NOW)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submit(TOKEN, List.of(item(UUID.randomUUID(), 5))))
                .isInstanceOf(NotFoundException.class);

        verifyNoInteractions(reviewService, bookingRepository);
    }

    @Test
    @DisplayName("should_throw404WithoutReviews_when_redeemedInviteResolvesNoBooking")
    void should_throw404WithoutReviews_when_redeemedInviteResolvesNoBooking() {
        ReviewInvite invite = bookingInvite();
        when(inviteRepository.consumeRedeemable(TOKEN_HASH, NOW)).thenReturn(Optional.of(invite));
        when(bookingRepository.findGuestReviewableSingleBookingWithGraph(SINGLE_BOOKING_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submit(TOKEN, List.of(item(SINGLE_BOOKING_ID, 5))))
                .isInstanceOf(NotFoundException.class);

        verifyNoInteractions(reviewService);
    }

    // ── POST /opt-out ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_insertInvitePhoneHash_when_optOutWithinThirtyDays")
    void should_insertInvitePhoneHash_when_optOutWithinThirtyDays() {
        ReviewInvite invite = bookingInvite();
        when(inviteRepository.findOptOutable(TOKEN_HASH, NOW.minus(Duration.ofDays(30))))
                .thenReturn(Optional.of(invite));

        service.optOut(TOKEN);

        verify(optOutRepository).insertIfAbsent(PHONE_HASH, NOW, "REVIEW_INVITE");
    }

    @Test
    @DisplayName("should_throw404_when_optOutTokenUnknownOrTooOld")
    void should_throw404_when_optOutTokenUnknownOrTooOld() {
        when(inviteRepository.findOptOutable(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.optOut(TOKEN)).isInstanceOf(NotFoundException.class);

        verify(optOutRepository, never()).insertIfAbsent(any(), any(), anyString());
    }
}
