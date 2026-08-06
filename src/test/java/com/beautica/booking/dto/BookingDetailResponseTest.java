package com.beautica.booking.dto;

import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.master.entity.Master;
import com.beautica.salon.entity.Salon;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.PriceType;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("BookingDetailResponse.from — unit")
class BookingDetailResponseTest {

    private static final OffsetDateTime STARTS_AT =
            OffsetDateTime.of(2025, 6, 15, 10, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime ENDS_AT =
            OffsetDateTime.of(2025, 6, 15, 11, 0, 0, 0, ZoneOffset.UTC);
    private static final Instant CREATED_AT = Instant.parse("2025-06-01T08:00:00Z");
    // Phase 29.2 — "now" for the derived awaitingClosure flag. NOW is strictly before ENDS_AT, so
    // every pre-29.2 test below (none of which assert on awaitingClosure) keeps its booking
    // "not yet elapsed" — the least surprising default for a fixture that predates the flag.
    private static final OffsetDateTime NOW = ENDS_AT.minusMinutes(30);

    private UUID bookingId;
    private UUID clientId;
    private UUID masterId;
    private UUID masterServiceId;

    // Separate User mocks so the test can verify traversal for client vs. master
    private User clientUser;
    private User masterUser;
    private Master master;
    private Booking booking;

    @BeforeEach
    void setUp() {
        bookingId = UUID.randomUUID();
        clientId = UUID.randomUUID();
        masterId = UUID.randomUUID();
        masterServiceId = UUID.randomUUID();

        clientUser = mock(User.class);
        when(clientUser.getId()).thenReturn(clientId);
        when(clientUser.getFirstName()).thenReturn("Олена");
        when(clientUser.getLastName()).thenReturn("Коваль");

        masterUser = mock(User.class);
        when(masterUser.getFirstName()).thenReturn("Наталія");
        when(masterUser.getLastName()).thenReturn("Бойко");
        when(masterUser.getProfessionalTitle()).thenReturn("Перукар-стиліст");
        when(masterUser.getLocationNote()).thenReturn("3-й поверх, код 1234");

        master = mock(Master.class);
        when(master.getId()).thenReturn(masterId);
        when(master.getUser()).thenReturn(masterUser);

        var serviceDef = mock(ServiceDefinition.class);
        when(serviceDef.getName()).thenReturn("Манікюр");

        var masterService = mock(MasterServiceAssignment.class);
        when(masterService.getId()).thenReturn(masterServiceId);
        when(masterService.getServiceDefinition()).thenReturn(serviceDef);

        booking = mock(Booking.class);
        when(booking.getId()).thenReturn(bookingId);
        when(booking.getClient()).thenReturn(clientUser);
        when(booking.getMaster()).thenReturn(master);
        when(booking.getMasterService()).thenReturn(masterService);
        when(booking.getStatus()).thenReturn(BookingStatus.CONFIRMED);
        when(booking.getStartsAt()).thenReturn(STARTS_AT);
        when(booking.getEndsAt()).thenReturn(ENDS_AT);
        when(booking.getPriceAtBooking()).thenReturn(new BigDecimal("350.00"));
        when(booking.getDurationMinutesAtBooking()).thenReturn(60);
        when(booking.getCreatedAt()).thenReturn(CREATED_AT);
        when(booking.getClientComment()).thenReturn("great service");
        when(booking.getProviderComment()).thenReturn("punctual");
    }

    @Test
    @DisplayName("maps every field correctly when booking is fully populated, including PII traversal")
    void should_mapAllFields_when_bookingIsValid() {
        var response = BookingDetailResponse.from(booking, true, true, "Київ", "Шевченківський", NOW);

        // shared fields
        assertThat(response.id()).isEqualTo(bookingId);
        assertThat(response.clientId()).isEqualTo(clientId);
        assertThat(response.masterId()).isEqualTo(masterId);
        assertThat(response.masterServiceId()).isEqualTo(masterServiceId);
        assertThat(response.serviceName()).isEqualTo("Манікюр");
        assertThat(response.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(response.priceAtBooking()).isEqualByComparingTo(new BigDecimal("350.00"));
        assertThat(response.priceMaxAtBooking())
                .as("no frozen ceiling on the row means a single price")
                .isNull();
        assertThat(response.durationMinutesAtBooking()).isEqualTo(60);

        // time zone conversion
        assertThat(response.startsAt().getZone()).isEqualTo(ZoneId.of("Europe/Kyiv"));
        assertThat(response.startsAt().getHour()).isEqualTo(13);
        assertThat(response.endsAt().getZone()).isEqualTo(ZoneId.of("Europe/Kyiv"));
        assertThat(response.endsAt().getHour()).isEqualTo(14);

        // createdAt
        assertThat(response.createdAt().getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(response.createdAt().toInstant()).isEqualTo(CREATED_AT);

        // client PII — sourced from booking.getClient()
        assertThat(response.clientFirstName()).isEqualTo("Олена");
        assertThat(response.clientLastName()).isEqualTo("Коваль");

        // master PII — sourced from booking.getMaster().getUser(), NOT booking.getClient()
        assertThat(response.masterFirstName()).isEqualTo("Наталія");
        assertThat(response.masterLastName()).isEqualTo("Бойко");
        assertThat(response.masterProfessionalTitle()).isEqualTo("Перукар-стиліст");

        // comments
        assertThat(response.clientComment()).isEqualTo("great service");
        assertThat(response.providerComment()).isEqualTo("punctual");

        // Phase 19.3 enrichment — passed-in canReview + resolved labels, category from service def
        assertThat(response.canReview()).isTrue();
        // providerCanReviewClient is likewise a passed-in value, not derived here — from() trusts
        // the caller (BookingService) the same way it trusts canReview.
        assertThat(response.providerCanReviewClient()).isTrue();
        assertThat(response.cityLabel()).isEqualTo("Київ");
        assertThat(response.districtLabel()).isEqualTo("Шевченківський");
        assertThat(response.salonName()).isNull();

        // locationNote — no salon on this booking, so it resolves from the master's own user row
        assertThat(response.locationNote()).isEqualTo("3-й поверх, код 1234");
    }

    @Test
    @DisplayName("clientComment and providerComment are null when absent on the booking")
    void should_returnNullComments_when_commentsAreAbsent() {
        when(booking.getClientComment()).thenReturn(null);
        when(booking.getProviderComment()).thenReturn(null);

        var response = BookingDetailResponse.from(booking, false, false, null, null, NOW);

        assertThat(response.clientComment()).isNull();
        assertThat(response.providerComment()).isNull();
    }

    @Test
    @DisplayName("clientId is null and clientFirstName/clientLastName fall back to the guest identity when the booking has no registered client (LINK)")
    void should_returnGuestIdentity_when_bookingHasNoRegisteredClient() {
        when(booking.getClient()).thenReturn(null);
        when(booking.getGuestName()).thenReturn("Оксана");
        when(booking.getGuestSurname()).thenReturn("Мельник");

        var response = BookingDetailResponse.from(booking, false, false, "Київ", "Шевченківський", NOW);

        assertThat(response.clientId()).isNull();
        assertThat(response.clientFirstName()).isEqualTo("Оксана");
        assertThat(response.clientLastName()).isEqualTo("Мельник");
        // master PII is unaffected — sourced from booking.getMaster().getUser(), never getClient()
        assertThat(response.masterFirstName()).isEqualTo("Наталія");
        assertThat(response.masterLastName()).isEqualTo("Бойко");
        assertThat(response.masterProfessionalTitle()).isEqualTo("Перукар-стиліст");
    }

    @Test
    @DisplayName("masterProfessionalTitle is null (not NPE) when the master never set one")
    void should_returnNullProfessionalTitle_when_masterHasNoTitle() {
        when(masterUser.getProfessionalTitle()).thenReturn(null);

        var response = BookingDetailResponse.from(booking, false, false, "Київ", "Шевченківський", NOW);

        assertThat(response.masterProfessionalTitle()).isNull();
        // the rest of the master row is unaffected by a missing title
        assertThat(response.masterFirstName()).isEqualTo("Наталія");
        assertThat(response.masterLastName()).isEqualTo("Бойко");
    }

    @Test
    @DisplayName("masterProfessionalTitle and locationNote are null (not NPE) on a guest (LINK) booking whose master has set neither — both fields hang off the master's user, never the (possibly-null) client")
    void should_returnNullProfessionalTitle_when_guestBookingAndMasterHasNoTitle() {
        when(booking.getClient()).thenReturn(null);
        when(booking.getGuestName()).thenReturn("Оксана");
        when(booking.getGuestSurname()).thenReturn("Мельник");
        when(masterUser.getProfessionalTitle()).thenReturn(null);
        when(masterUser.getLocationNote()).thenReturn(null);

        var response = BookingDetailResponse.from(booking, false, false, "Київ", "Шевченківський", NOW);

        assertThat(response.clientId()).isNull();
        assertThat(response.masterProfessionalTitle()).isNull();
        assertThat(response.locationNote()).isNull();
    }

    // ── locationNote — salon-vs-independent resolution (mirrors street/buildingNo) ────

    @Test
    @DisplayName("locationNote resolves from the master's OWN user row when the BOOKING carries no "
            + "salon (independent-master visit) — and still does after the master later joins one")
    void should_resolveLocationNoteFromMaster_when_masterIsIndependent() {
        when(masterUser.getLocationNote()).thenReturn("Дзвонити двічі");
        // booking.getSalon() is unstubbed on this mock -> null, exercising the independent branch.
        // The master HAS since joined a salon (phase 242's four-case table, row 3): the visit was
        // genuinely at the master's own address, so the salon must not be consulted at all.
        var laterSalon = mock(Salon.class);
        lenient().when(laterSalon.getName()).thenReturn("Joined-Later Studio");
        lenient().when(laterSalon.getLocationNote()).thenReturn("Later salon note — must NOT surface");
        when(master.getSalon()).thenReturn(laterSalon);

        var response = BookingDetailResponse.from(booking, false, false, "Київ", "Шевченківський", NOW);

        assertThat(response.salonName()).isNull();
        assertThat(response.locationNote()).isEqualTo("Дзвонити двічі");
    }

    @Test
    @DisplayName("locationNote resolves from the BOOKED salon, never the master's own note, when "
            + "the visit was made at a salon")
    void should_resolveLocationNoteFromSalon_when_masterIsSalonEmployed() {
        // The master's own note is set to a DIFFERENT value to prove the salon wins.
        when(masterUser.getLocationNote()).thenReturn("Master's own note — must NOT surface");
        var salon = mock(Salon.class);
        when(salon.getName()).thenReturn("Glamour Studio");
        when(salon.getLocationNote()).thenReturn("3-й поверх, код 1234");
        when(booking.getSalon()).thenReturn(salon);

        var response = BookingDetailResponse.from(booking, false, false, "Київ", "Шевченківський", NOW);

        assertThat(response.salonName()).isEqualTo("Glamour Studio");
        assertThat(response.locationNote()).isEqualTo("3-й поверх, код 1234");
    }

    // ── priceMaxAtBooking — the frozen snapshot column (V119), NOT a live derivation. The
    //    creation-time rule that produced the stored value is pinned by BookingPriceRangeTest.

    @Test
    @DisplayName("priceMaxAtBooking carries the frozen ceiling stored on the booking row")
    void should_returnFrozenCeiling_when_bookingHasOne() {
        when(booking.getPriceMaxAtBooking()).thenReturn(new BigDecimal("500.00"));

        var response = BookingDetailResponse.from(booking, false, false, "Київ", "Шевченківський", NOW);

        assertThat(response.priceMaxAtBooking()).isEqualByComparingTo(new BigDecimal("500.00"));
    }

    @Test
    @DisplayName("priceMaxAtBooking is null when the booking froze no ceiling — a single price")
    void should_returnNullPriceMax_when_bookingFrozeNoCeiling() {
        when(booking.getPriceMaxAtBooking()).thenReturn(null);

        var response = BookingDetailResponse.from(booking, false, false, "Київ", "Шевченківський", NOW);

        assertThat(response.priceMaxAtBooking()).isNull();
    }

    @Test
    @DisplayName("priceMaxAtBooking ignores the service's CURRENT priceType/priceMax — a provider "
            + "editing their service must not rewrite a band the client already agreed to")
    void should_keepFrozenCeiling_when_providerEditsServiceAfterBooking() {
        when(booking.getPriceMaxAtBooking()).thenReturn(new BigDecimal("500.00"));
        // The provider has since widened the live service far beyond the agreed band.
        var serviceDef = booking.getMasterService().getServiceDefinition();
        lenient().when(serviceDef.getPriceType()).thenReturn(PriceType.RANGE);
        lenient().when(serviceDef.getPriceMax()).thenReturn(new BigDecimal("9999.00"));
        lenient().when(booking.getMasterService().getPriceOverride()).thenReturn(null);

        var response = BookingDetailResponse.from(booking, false, false, "Київ", "Шевченківський", NOW);

        assertThat(response.priceMaxAtBooking())
                .as("the agreed ceiling, not the edited one")
                .isEqualByComparingTo(new BigDecimal("500.00"));
    }

    @Test
    @DisplayName("a FIXED-service booking that froze a ceiling still shows it — the snapshot wins "
            + "even when the live service could no longer produce one")
    void should_keepFrozenCeiling_when_providerFlippedServiceToFixedAfterBooking() {
        when(booking.getPriceMaxAtBooking()).thenReturn(new BigDecimal("500.00"));
        var serviceDef = booking.getMasterService().getServiceDefinition();
        lenient().when(serviceDef.getPriceType()).thenReturn(PriceType.FIXED);
        lenient().when(serviceDef.getPriceMax()).thenReturn(null);

        var response = BookingDetailResponse.from(booking, false, false, "Київ", "Шевченківський", NOW);

        assertThat(response.priceMaxAtBooking()).isEqualByComparingTo(new BigDecimal("500.00"));
    }

    // ── clientAvatarUrl — deliberate client-PII widening (provider timeline photo) ────
    //
    // QA-authored. The field ships a provider the LIKENESS of the client who booked with them,
    // so the three branches below are the whole contract: (1) a registered client's own photo
    // reaches the DTO and is NOT confused with the master's, (2) a guest booking has no photo
    // and deliberately no fallback, (3) a registered client who never uploaded one is null.
    //
    // Why the distinct-URL fixture in (1) matters: clientAvatarUrl and masterAvatarUrl are
    // adjacent String reads off two different User graphs (client vs master.getUser()). The
    // shipped mapper is `client != null ? client.getAvatarUrl() : null` — one edit away from
    // `masterUser.getAvatarUrl()`. Every pre-existing fixture in this suite leaves the master's
    // avatar unstubbed (null) and the client's unstubbed (null), so that swap would have been
    // invisible: null == null. Seeding two DIFFERENT non-null URLs is what makes the swap fail.

    private static final String CLIENT_AVATAR = "https://cdn.beautica.test/avatars/client-olena.jpg";
    private static final String MASTER_AVATAR = "https://cdn.beautica.test/avatars/master-nataliia.jpg";

    @Test
    @DisplayName("clientAvatarUrl carries the CLIENT's own photo — never the master's — when the "
            + "booking has a registered client (the two avatar fields read two different User "
            + "graphs and must not be swapped)")
    void should_returnClientsOwnAvatar_when_bookingHasRegisteredClient() {
        when(clientUser.getAvatarUrl()).thenReturn(CLIENT_AVATAR);
        when(masterUser.getAvatarUrl()).thenReturn(MASTER_AVATAR);

        var response = BookingDetailResponse.from(booking, false, false, "Київ", "Шевченківський", NOW);

        assertThat(response.clientAvatarUrl())
                .as("must be booking.getClient().getAvatarUrl(); a copy-paste of the adjacent "
                        + "masterUser.getAvatarUrl() read would show the provider their OWN photo "
                        + "on every client card, actual=%s", response.clientAvatarUrl())
                .isEqualTo(CLIENT_AVATAR)
                .isNotEqualTo(MASTER_AVATAR);
        assertThat(response.masterAvatarUrl())
                .as("the sibling field must be unaffected — proves the assertion above is not "
                        + "passing because both fields collapsed onto the client")
                .isEqualTo(MASTER_AVATAR);
    }

    @Test
    @DisplayName("clientAvatarUrl is null (not NPE, and deliberately NOT falling back to the guest "
            + "identity the way clientFirstName/clientLastName do) on a guest (LINK) booking, while "
            + "masterAvatarUrl is still served")
    void should_returnNullClientAvatar_when_bookingHasNoRegisteredClient() {
        when(booking.getClient()).thenReturn(null);
        when(booking.getGuestName()).thenReturn("Оксана");
        when(booking.getGuestSurname()).thenReturn("Мельник");
        when(masterUser.getAvatarUrl()).thenReturn(MASTER_AVATAR);

        var response = BookingDetailResponse.from(booking, false, false, "Київ", "Шевченківський", NOW);

        assertThat(response.clientAvatarUrl())
                .as("a guest has no account and so no photo — and unlike the name there is nothing "
                        + "to fall back TO; the card renders the generic glyph")
                .isNull();
        assertThat(response.clientFirstName())
                .as("non-vacuity: the guest identity fallback that DOES exist still fired, so the "
                        + "null above is the avatar branch specifically, not a dead fixture")
                .isEqualTo("Оксана");
        assertThat(response.masterAvatarUrl())
                .as("the master's likeness is unaffected by the client being absent — it hangs off "
                        + "master.getUser(), never the (null) client")
                .isEqualTo(MASTER_AVATAR);
    }

    @Test
    @DisplayName("clientAvatarUrl is null for a registered client who never uploaded a photo — the "
            + "second documented null case, indistinguishable from the guest case by contract")
    void should_returnNullClientAvatar_when_registeredClientNeverUploadedOne() {
        when(clientUser.getAvatarUrl()).thenReturn(null);

        var response = BookingDetailResponse.from(booking, false, false, "Київ", "Шевченківський", NOW);

        assertThat(response.clientAvatarUrl()).isNull();
        assertThat(response.clientId())
                .as("non-vacuity: this IS a registered client, so the null came from an empty "
                        + "avatar column and not from the guest null-guard")
                .isEqualTo(clientId);
    }

    // ── awaitingClosure (Phase 29.1/29.2) — derived, never-persisted flag ──────────────────────
    //
    // awaitingClosure and canReview are two INDEPENDENT boolean/OffsetDateTime inputs this factory
    // simply passes/derives through — from() never derives canReview FROM awaitingClosure or vice
    // versa. Since the review-eligibility widening (BookingClosureRule#isReviewEligible), the REAL
    // caller (BookingService) does compute canReview=true for many elapsed-CONFIRMED bookings too
    // — but that computation happens upstream, in BookingService, never inside this DTO factory.
    // The test below therefore deliberately passes a canReview value that would DISAGREE with what
    // the real service computes for an elapsed CONFIRMED booking, to prove from() never overrides
    // or derives it — only the caller's business logic (unit-tested separately in
    // BookingServiceTest) decides the real value.

    @Test
    @DisplayName("awaitingClosure is TRUE for an elapsed CONFIRMED booking; canReview is simply "
            + "whatever the caller passed in — from() never derives one from the other")
    void should_returnTrueAwaitingClosure_when_confirmedAndElapsed_independentOfPassedInCanReview() {
        var response = BookingDetailResponse.from(
                booking, false, false, "Київ", "Шевченківський", ENDS_AT.plusMinutes(1));

        assertThat(response.awaitingClosure()).isTrue();
        assertThat(response.canReview())
                .as("from() must echo the caller-supplied canReview verbatim, not derive it from "
                        + "awaitingClosure — the real derivation lives in BookingService#canReview")
                .isFalse();
    }

    @Test
    @DisplayName("awaitingClosure is FALSE for a future CONFIRMED booking (now < endsAt)")
    void should_returnFalseAwaitingClosure_when_confirmedAndNotYetElapsed() {
        var response = BookingDetailResponse.from(
                booking, false, false, "Київ", "Шевченківський", ENDS_AT.minusMinutes(1));

        assertThat(response.awaitingClosure()).isFalse();
    }

    @Test
    @DisplayName("awaitingClosure is FALSE at the exact endsAt boundary — strict '<', half-open")
    void should_returnFalseAwaitingClosure_when_nowEqualsEndsAt() {
        var response = BookingDetailResponse.from(
                booking, false, false, "Київ", "Шевченківський", ENDS_AT);

        assertThat(response.awaitingClosure()).isFalse();
    }

    @Test
    @DisplayName("awaitingClosure is FALSE for a COMPLETED booking even with a FUTURE endsAt — the "
            + "Option-A hole: closed is closed, regardless of elapsed time")
    void should_returnFalseAwaitingClosure_when_completedWithFutureEndsAt() {
        when(booking.getStatus()).thenReturn(BookingStatus.COMPLETED);

        var response = BookingDetailResponse.from(
                booking, true, false, "Київ", "Шевченківський", ENDS_AT.minusYears(1));

        assertThat(response.awaitingClosure())
                .as("endsAt is well in the future of now, yet COMPLETED must never be awaitingClosure")
                .isFalse();
    }

    @Test
    @DisplayName("awaitingClosure is FALSE for NOT_COMPLETED/CANCELLED/DECLINED regardless of "
            + "endsAt — only an elapsed CONFIRMED booking ever reads true")
    void should_returnFalseAwaitingClosure_when_terminalNonCompletedStatusRegardlessOfEndsAt() {
        for (BookingStatus status : new BookingStatus[] {
                BookingStatus.NOT_COMPLETED, BookingStatus.CANCELLED, BookingStatus.DECLINED}) {
            when(booking.getStatus()).thenReturn(status);

            var elapsed = BookingDetailResponse.from(
                    booking, false, false, "Київ", "Шевченківський", ENDS_AT.plusYears(1));
            var notYetElapsed = BookingDetailResponse.from(
                    booking, false, false, "Київ", "Шевченківський", ENDS_AT.minusYears(1));

            assertThat(elapsed.awaitingClosure()).as("%s, elapsed", status).isFalse();
            assertThat(notYetElapsed.awaitingClosure()).as("%s, not elapsed", status).isFalse();
        }
    }

    @Test
    @DisplayName("the mapper is called twice with two different 'now' values on the SAME entity "
            + "and returns two different flags — now is a real parameter, never captured from a "
            + "static clock")
    void should_returnDifferentFlags_when_calledTwiceWithDifferentNowOnSameEntity() {
        var beforeElapse = BookingDetailResponse.from(
                booking, false, false, "Київ", "Шевченківський", ENDS_AT.minusMinutes(1));
        var afterElapse = BookingDetailResponse.from(
                booking, false, false, "Київ", "Шевченківський", ENDS_AT.plusMinutes(1));

        assertThat(beforeElapse.awaitingClosure()).isFalse();
        assertThat(afterElapse.awaitingClosure()).isTrue();
    }

    // ── Phase B1 — master rating aggregate ────────────────────────────────────────────────────
    //    masters.avg_rating is NOT NULL DEFAULT 0.00 (V4) and recalculateMasterRating writes
    //    COALESCE(AVG(...), 0), so an unreviewed master stores a literal 0.00. That is a storage
    //    artefact, not a rating, and must never reach the wire — same rule as
    //    ReviewService#getMasterReviewSummary.

    @Test
    @DisplayName("masterAvgRating/masterReviewCount are read off the already-loaded Master row when "
            + "the master has reviews")
    void should_mapMasterRating_when_masterHasReviews() {
        when(master.getAvgRating()).thenReturn(new BigDecimal("4.75"));
        when(master.getReviewCount()).thenReturn(12);

        var response = BookingDetailResponse.from(booking, false, false, "Київ", "Шевченківський", NOW);

        assertThat(response.masterAvgRating()).isEqualByComparingTo(new BigDecimal("4.75"));
        assertThat(response.masterReviewCount()).isEqualTo(12);
    }

    @Test
    @DisplayName("masterAvgRating is NULL — never 0.00 — for a master with zero reviews, while "
            + "masterReviewCount stays 0 (a true fact, unlike a fabricated zero-star average)")
    void should_returnNullAvgRating_when_masterHasNoReviews() {
        when(master.getAvgRating()).thenReturn(new BigDecimal("0.00"));
        when(master.getReviewCount()).thenReturn(0);

        var response = BookingDetailResponse.from(booking, false, false, "Київ", "Шевченківський", NOW);

        assertThat(response.masterAvgRating()).isNull();
        assertThat(response.masterReviewCount()).isZero();
    }

    @Test
    @DisplayName("masterAvgRatingOrNull suppresses the stored average only at reviewCount == 0 — "
            + "a single review with a genuine 1.00 average is still surfaced")
    void should_surfaceGenuineLowRating_when_masterHasExactlyOneReview() {
        assertThat(BookingDetailResponse.masterAvgRatingOrNull(0, new BigDecimal("0.00"))).isNull();
        assertThat(BookingDetailResponse.masterAvgRatingOrNull(1, new BigDecimal("1.00")))
                .isEqualByComparingTo(new BigDecimal("1.00"));
    }

    // ── Phase B2 — the booking's salon snapshot ───────────────────────────────────────────────
    //    salonId must come from booking.getSalon() — the row ReviewService#createReview stamps
    //    onto the review and publishes on ReviewCreatedEvent — and NOT from master.getSalon(),
    //    which is the master's LIVE affiliation. The two diverge after a salon rotation, and a
    //    master-derived id would have the mobile client invalidate the wrong salon's caches.

    @Test
    @DisplayName("salonId is read from the BOOKING's own salon when the booking was made at a salon")
    void should_mapSalonId_when_bookingCarriesSalon() {
        UUID salonId = UUID.randomUUID();
        var bookingSalon = mock(Salon.class);
        when(bookingSalon.getId()).thenReturn(salonId);
        when(booking.getSalon()).thenReturn(bookingSalon);

        var response = BookingDetailResponse.from(booking, false, false, "Київ", "Шевченківський", NOW);

        assertThat(response.salonId()).isEqualTo(salonId);
    }

    @Test
    @DisplayName("salonId is NULL for an independent master's booking, and the booking still maps "
            + "cleanly (no NPE, nothing dropped)")
    void should_returnNullSalonId_when_bookingHasNoSalon() {
        // booking.getSalon() is unstubbed — Mockito returns null, exactly like an
        // INDEPENDENT_MASTER row whose salon_id is NULL.
        var response = BookingDetailResponse.from(booking, false, false, "Київ", "Шевченківський", NOW);

        assertThat(response.salonId()).isNull();
        assertThat(response.id()).isEqualTo(bookingId);
        assertThat(response.salonName())
                .as("master has no salon either, so the display name is null too")
                .isNull();
    }

    /**
     * Phase 242 — the whole address block follows the BOOKING's salon.
     *
     * <p>Both salon mocks stub EVERY read the mapper makes, with DISTINCT non-null values. That is
     * load-bearing, not thoroughness: an unstubbed getter returns {@code null} on both sides, which
     * makes {@code isNotEqualTo(salonB…)} pass vacuously and turns the security assertion into a
     * no-op — the exact trap this DTO's test history is full of.
     */
    @Test
    @DisplayName("the whole address block follows the BOOKING's salon after a rotation — salonName, "
            + "street, buildingNo and locationNote are salon A's, and NEVER salon B's")
    void should_useBookingSalon_when_masterHasRotatedToADifferentSalon() {
        UUID bookedSalonId = UUID.randomUUID();
        UUID currentSalonId = UUID.randomUUID();

        // Salon A — where the visit was actually booked (bookings.salon_id).
        var bookedSalon = mock(Salon.class);
        when(bookedSalon.getId()).thenReturn(bookedSalonId);
        when(bookedSalon.getName()).thenReturn("Booked Studio A");
        when(bookedSalon.getStreet()).thenReturn("Volodymyrska");
        when(bookedSalon.getBuildingNo()).thenReturn("55");
        when(bookedSalon.getLocationNote()).thenReturn("A: 3-й поверх, код 1234");
        when(booking.getSalon()).thenReturn(bookedSalon);

        // Salon B — where the master works TODAY (masters.salon_id), a different row with a
        // different door code. Nothing about it may reach the response.
        var currentSalon = mock(Salon.class);
        // Every getter is stubbed even though the CORRECT implementation calls none of them: a
        // wrong-source regression then fails with salon B's actual value (a readable diagnostic),
        // and the isNotEqualTo assertions below compare against values a code path can really
        // return instead of being dead. Safe to stub unused — this class uses plain mock() with no
        // MockitoExtension, so strict stubs are not in force.
        lenient().when(currentSalon.getId()).thenReturn(currentSalonId);
        lenient().when(currentSalon.getName()).thenReturn("Glamour Studio B");
        lenient().when(currentSalon.getStreet()).thenReturn("Khreshchatyk");
        lenient().when(currentSalon.getBuildingNo()).thenReturn("22");
        lenient().when(currentSalon.getLocationNote()).thenReturn("B: 5-й поверх, код 9999");
        when(master.getSalon()).thenReturn(currentSalon);

        // The master's own personal row differs again — it must not surface either, because the
        // booking DOES carry a salon (the COALESCE-fallthrough guard, unchanged by phase 242).
        when(masterUser.getStreet()).thenReturn("Master's own street — must NOT surface");
        when(masterUser.getBuildingNo()).thenReturn("MASTER-99");
        when(masterUser.getLocationNote()).thenReturn("Master's own note — must NOT surface");

        var response = BookingDetailResponse.from(booking, false, false, "Київ", "Шевченківський", NOW);

        assertThat(response.salonId())
                .as("the review is stamped with booking.salon, so that is the id whose aggregates "
                        + "moved and the one the client must invalidate")
                .isEqualTo(bookedSalonId)
                .isNotEqualTo(currentSalonId);
        assertThat(response.salonName())
                .as("phase 242 INVERTED this: salonName used to track master.getSalon() and now "
                        + "shares salonId's source — the two can no longer disagree")
                .isEqualTo("Booked Studio A")
                .isNotEqualTo("Glamour Studio B");
        assertThat(response.street())
                .isEqualTo("Volodymyrska")
                .isNotEqualTo("Khreshchatyk");
        assertThat(response.buildingNo())
                .isEqualTo("55")
                .isNotEqualTo("22");
        // THE security assertion. locationNote holds door codes by its own @Schema contract, so
        // serving salon B's to a client who only ever booked at salon A hands out premises access
        // they were never granted. The negative is asserted explicitly: a positive-only check
        // would pass even if both fixtures happened to carry the same note.
        assertThat(response.locationNote())
                .as("the client booked at salon A — they must get A's door code and NEVER B's")
                .isEqualTo("A: 3-й поверх, код 1234")
                .isNotEqualTo("B: 5-й поверх, код 9999")
                .isNotEqualTo("Master's own note — must NOT surface");
    }
}
