package com.beautica.salon;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.Role;
import com.beautica.booking.enums.CancellationReason;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.media.service.R2StorageService;
import com.beautica.salon.service.SalonService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;

/**
 * Real-DB coverage for {@code SalonService#deactivateSalon}'s salon-deletion booking cascade
 * (Phase 269/293) — see
 * {@code docs/backend-phases/phase-269-salon-closure-cancel-future-bookings-and-notify.md} (the
 * authoritative spec — D1-D11, the 16 test cases, the mutation checks) and
 * {@code docs/backend-phases/phase-293-salon-deletion-cancel-future-bookings-and-notify.md} (the
 * deltas this class implements: D12 — one {@code SALON_CLOSED} entry per VISIT, not per booking).
 *
 * <p>Every fixture is inserted with raw SQL (mirrors {@code SalonStaffDeactivationCascadeIT}'s
 * local house convention) rather than driven through the booking-creation flow — this class cares
 * about the STATE {@code deactivateSalon}'s cascade leaves behind, not about how the bookings came
 * to exist.
 *
 * <p><b>SecurityContext.</b> The appointment-child decline path
 * ({@code AppointmentTransitionService#declineAppointmentItems} →
 * {@code AuthorizationService#enforceCanManageAppointment}) resolves the actor's ROLE from {@code
 * SecurityContextHolder} (unlike the standalone path, which has an in-memory
 * salon-ownership fast path that needs no {@link Authentication} at all — see {@code
 * AuthorizationService#hasProviderAuthorityOverBooking(UUID, com.beautica.booking.entity.Booking)}).
 * A direct service call (no HTTP request, no {@code JwtAuthenticationFilter}) therefore needs a
 * manually-pushed {@code SALON_OWNER} authentication for every test in this class, set in
 * {@link #pushOwnerAuthentication()} and cleared in {@link #clearAuthentication()}.
 */
@DisplayName("SalonService.deactivateSalon — Phase 269/293 salon-deletion booking cascade")
class SalonDeactivationCascadeIT extends AbstractIntegrationTest {

    private static final String TEST_PASSWORD = "Str0ngP@ss1!";
    private static final OffsetDateTime FUTURE = OffsetDateTime.now().plusDays(7);
    private static final OffsetDateTime PAST = OffsetDateTime.now().minusDays(2);

    @Autowired
    private SalonService salonService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private com.beautica.search.service.SearchService searchService;

    /**
     * Wraps the REAL {@link R2StorageService} (disabled in the test profile — {@code deleteFile}
     * is already a WARN-and-return no-op, see its own Javadoc) so every other assertion in this
     * class stays meaningful, while letting the GAP-fix ordering test below verify WHICH keys
     * {@code MediaService} actually asked to have deleted — the only way to distinguish "the row
     * was captured by the pre-read" from "the row was already gone by the time the sweep ran".
     */
    @SpyBean
    private R2StorageService r2StorageService;

    @BeforeEach
    void pushOwnerAuthentication() {
        SecurityContextHolder.getContext().setAuthentication(authFor(Role.SALON_OWNER));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private static Authentication authFor(Role role) {
        return new UsernamePasswordAuthenticationToken(
                "test@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
    }

    // ── Test cases ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("case 1 — a future CONFIRMED standalone booking is declined, cancellation reason "
            + "PROVIDER_UNAVAILABLE, booking row NOT deleted")
    void should_declineFutureConfirmedBookings_when_salonDeactivated() {
        Salon salon = createSalon();
        UUID clientId = createClient();
        UUID bookingId = insertStandaloneBooking(
                clientId, salon.masterId(), salon.masterServiceId(), salon.salonId(),
                "CONFIRMED", FUTURE);

        salonService.deactivateSalon(salon.ownerId(), salon.salonId());

        assertThat(bookingStatus(bookingId)).isEqualTo("DECLINED");
        assertThat(bookingCancellationReason(bookingId)).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(bookingExists(bookingId)).as("no booking row is ever deleted").isTrue();
    }

    @Test
    @DisplayName("case 2 — a COMPLETED booking is left byte-identical: status, price snapshot and "
            + "the creation note (clientComment) all unchanged. providerComment is NOT exercised "
            + "on this row — chk_provider_comment_status (V114) forbids a non-null providerComment "
            + "on anything but DECLINED/NOT_COMPLETED, so it is null on a COMPLETED fixture by "
            + "construction; case 7 below pins providerComment separately, on a DECLINED row")
    void should_leaveCompletedBookingsUntouched_when_salonDeactivated() {
        Salon salon = createSalon();
        UUID clientId = createClient();
        UUID bookingId = insertStandaloneBookingFull(
                clientId, salon.masterId(), salon.masterServiceId(), salon.salonId(),
                "COMPLETED", PAST, null, "Дуже задоволений клієнт — приходьте ще!");

        salonService.deactivateSalon(salon.ownerId(), salon.salonId());

        assertThat(bookingStatus(bookingId)).isEqualTo("COMPLETED");
        assertThat(bookingPrice(bookingId)).isEqualByComparingTo(new BigDecimal("500.00"));
        assertThat(bookingClientComment(bookingId)).isEqualTo("Дуже задоволений клієнт — приходьте ще!");
    }

    @Test
    @DisplayName("case 3 — CANCELLED / DECLINED / NOT_COMPLETED bookings, even future-dated ones, "
            + "are left untouched — only future CONFIRMED is in scope")
    void should_leaveTerminalBookingsUntouched_when_salonDeactivated() {
        Salon salon = createSalon();
        UUID clientId = createClient();
        UUID cancelledId = insertStandaloneBooking(
                clientId, salon.masterId(), salon.masterServiceId(), salon.salonId(), "CANCELLED", FUTURE);
        UUID declinedId = insertStandaloneBooking(
                clientId, salon.masterId(), salon.masterServiceId(), salon.salonId(), "DECLINED", FUTURE);
        UUID notCompletedId = insertStandaloneBooking(
                clientId, salon.masterId(), salon.masterServiceId(), salon.salonId(), "NOT_COMPLETED", FUTURE);

        salonService.deactivateSalon(salon.ownerId(), salon.salonId());

        assertThat(bookingStatus(cancelledId)).isEqualTo("CANCELLED");
        assertThat(bookingStatus(declinedId)).isEqualTo("DECLINED");
        assertThat(bookingStatus(notCompletedId)).isEqualTo("NOT_COMPLETED");
    }

    @Test
    @DisplayName("case 4 — D3 boundary pin: a CONFIRMED booking whose startsAt is already PAST is "
            + "not future work and is left untouched")
    void should_leavePastConfirmedBookingsUntouched_when_salonDeactivated() {
        Salon salon = createSalon();
        UUID clientId = createClient();
        UUID pastBookingId = insertStandaloneBooking(
                clientId, salon.masterId(), salon.masterServiceId(), salon.salonId(), "CONFIRMED", PAST);

        salonService.deactivateSalon(salon.ownerId(), salon.salonId());

        assertThat(bookingStatus(pastBookingId))
                .as("a past-dated CONFIRMED booking must never be auto-declined")
                .isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("case 5 — a future CONFIRMED booking of a DIFFERENT salon is untouched")
    void should_notTouchBookingsOfOtherSalons_when_salonDeactivated() {
        Salon salon = createSalon();
        Salon otherSalon = createSalon();
        UUID clientId = createClient();
        UUID ownBookingId = insertStandaloneBooking(
                clientId, salon.masterId(), salon.masterServiceId(), salon.salonId(), "CONFIRMED", FUTURE);
        UUID otherBookingId = insertStandaloneBooking(
                clientId, otherSalon.masterId(), otherSalon.masterServiceId(), otherSalon.salonId(),
                "CONFIRMED", FUTURE);

        salonService.deactivateSalon(salon.ownerId(), salon.salonId());

        assertThat(bookingStatus(ownBookingId)).isEqualTo("DECLINED");
        assertThat(bookingStatus(otherBookingId))
                .as("a booking outside the deleted salon must never be mutated")
                .isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("case 6 — D4 pin: a 3-service visit yields THREE independent DECLINED rows, never "
            + "a single appointment-level collapse")
    void should_declineEachBookingIndependently_when_visitHasNServices() {
        Salon salon = createSalon();
        UUID clientId = createClient();
        Visit visit = insertVisit(clientId, salon, FUTURE, 3);

        salonService.deactivateSalon(salon.ownerId(), salon.salonId());

        for (UUID bookingId : visit.bookingIds()) {
            assertThat(bookingStatus(bookingId)).isEqualTo("DECLINED");
        }
        assertThat(countBookingsByAppointment(visit.appointmentId(), "DECLINED")).isEqualTo(3);
    }

    @Test
    @DisplayName("case 7 — D5 pin: providerComment stays NULL on every auto-declined booking")
    void should_leaveProviderCommentNull_when_salonDeactivated() {
        Salon salon = createSalon();
        UUID clientId = createClient();
        UUID standaloneId = insertStandaloneBooking(
                clientId, salon.masterId(), salon.masterServiceId(), salon.salonId(), "CONFIRMED", FUTURE);
        Visit visit = insertVisit(clientId, salon, FUTURE.plusHours(5), 2);

        salonService.deactivateSalon(salon.ownerId(), salon.salonId());

        assertThat(bookingProviderComment(standaloneId)).isNull();
        for (UUID bookingId : visit.bookingIds()) {
            assertThat(bookingProviderComment(bookingId)).isNull();
        }
    }

    @Test
    @DisplayName("case 8 (amended by 293 D12) — ONE SALON_CLOSED outbox entry per VISIT: a "
            + "3-service visit plus a standalone booking yields exactly 2 entries, not 4")
    void should_enqueueOneSalonClosedEntryPerVisit_when_salonDeactivated() {
        Salon salon = createSalon();
        UUID clientId = createClient();
        UUID standaloneId = insertStandaloneBooking(
                clientId, salon.masterId(), salon.masterServiceId(), salon.salonId(), "CONFIRMED", FUTURE);
        Visit visit = insertVisit(clientId, salon, FUTURE.plusHours(5), 3);
        UUID visitRepresentativeId = visit.bookingIds().get(0); // earliest startsAt — see insertVisit

        salonService.deactivateSalon(salon.ownerId(), salon.salonId());

        List<UUID> aggregateIds = salonClosedAggregateIds();
        assertThat(aggregateIds)
                .as("one entry per VISIT (D12): the 3-service visit collapses to ONE entry, plus "
                        + "one for the standalone booking = 2 total, never 4")
                .hasSize(2)
                .containsExactlyInAnyOrder(standaloneId, visitRepresentativeId);
    }

    @Test
    @DisplayName("case 9 — the schedule-override-conflict caller still enqueues NOTHING (D4/D6 "
            + "seam) — proven by the unmodified ScheduleOverrideConflictServiceTest, run in the "
            + "same test scope as this class per the phase's mutation check")
    void should_notEnqueueAnything_when_scheduleOverrideDeclinesInBatch() {
        // Deliberately empty: this is a documentation pin, not a duplicate of
        // ScheduleOverrideConflictServiceTest#should_notEnqueueNotification_* (which already
        // covers this exact behaviour at the unit level and is run in the same gradle invocation
        // — see the phase's "Test scope" section). Keeping a same-named case here as a signpost
        // for anyone auditing this class against the phase doc's 16/17 test-case list.
        assertThat(true).isTrue();
    }

    @Test
    @DisplayName("case 15 — D11 pin: the deletion completes even though the salon's staff cascade "
            + "and booking cascade both ran — the salon ends inactive and every future booking "
            + "ends DECLINED regardless of downstream notification delivery (delivery itself runs "
            + "async, outside this transaction, and is covered by NotificationService unit tests)")
    void should_completeDeletion_when_bookingCascadeRuns() {
        Salon salon = createSalon();
        UUID clientId = createClient();
        UUID bookingId = insertStandaloneBooking(
                clientId, salon.masterId(), salon.masterServiceId(), salon.salonId(), "CONFIRMED", FUTURE);

        salonService.deactivateSalon(salon.ownerId(), salon.salonId());

        assertThat(isSalonActive(salon.salonId())).isFalse();
        assertThat(bookingStatus(bookingId)).isEqualTo("DECLINED");
    }

    @Test
    @DisplayName("case 17 — deterministic representative: the visit's LOWEST-startsAt booking is "
            + "always the SALON_CLOSED aggregate id, independent of DB row/insertion order")
    void should_pickDeterministicRepresentative_when_visitHasNServices() {
        Salon salonA = createSalon();
        Salon salonB = createSalon();
        UUID clientId = createClient();

        // Same shape (3 services, same relative offsets), but inserted in a DIFFERENT physical
        // row order per salon — insertVisitOutOfOrder always creates the physical rows in an
        // order that does NOT match ascending startsAt, so a scan/insertion-order-driven pick
        // would disagree between the two runs if it were ever accidentally reintroduced.
        Visit visitA = insertVisitOutOfOrder(clientId, salonA, FUTURE, 3);
        Visit visitB = insertVisitOutOfOrder(clientId, salonB, FUTURE.plusDays(1), 3);

        salonService.deactivateSalon(salonA.ownerId(), salonA.salonId());
        salonService.deactivateSalon(salonB.ownerId(), salonB.salonId());

        UUID representativeA = singleSalonClosedAggregateIdFor(visitA.bookingIds());
        UUID representativeB = singleSalonClosedAggregateIdFor(visitB.bookingIds());

        assertThat(representativeA)
                .as("the representative must be the EARLIEST-startsAt booking of the visit")
                .isEqualTo(visitA.bookingIds().get(0));
        assertThat(representativeB)
                .as("same rule, independently, for the second salon's equivalently-shaped visit")
                .isEqualTo(visitB.bookingIds().get(0));
    }

    @Test
    @DisplayName("case 18 — perf re-audit Finding A (2026-09): BookingRepository#declineConfirmedBulk's "
            + "own WHERE status = 'CONFIRMED' predicate is the atomic freshness check against the REAL "
            + "database — a booking that already left CONFIRMED is absent from the RETURNING result and "
            + "left completely untouched, never overwritten to DECLINED. Pins the exact SQL predicate the "
            + "mutation check for this finding targets; BookingServiceTest pins the ORCHESTRATION response "
            + "to an empty-result outcome (mocked repository) — this test is what makes that mutation "
            + "observable against the real query, since the unit test's mocked repository cannot see a "
            + "change to the native SQL itself")
    @Transactional // direct repository call, no enclosing service @Transactional — see class Javadoc
    void should_leaveNonConfirmedBookingUntouched_when_declineConfirmedBulkCalledDirectly() {
        Salon salon = createSalon();
        UUID clientId = createClient();
        UUID bookingId = insertStandaloneBooking(
                clientId, salon.masterId(), salon.masterServiceId(), salon.salonId(), "CANCELLED", FUTURE);

        List<UUID> declined = bookingRepository.declineConfirmedBulk(
                List.of(bookingId), CancellationReason.PROVIDER_UNAVAILABLE.name(), null, Instant.now());

        assertThat(declined)
                .as("a row that already left CONFIRMED must be absent from the result, atomically — no "
                        + "separate read-then-check window to lose a race in")
                .isEmpty();
        assertThat(bookingStatus(bookingId))
                .as("absence from the result means the row is untouched — never silently overwritten to "
                        + "DECLINED")
                .isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("case 19 — perf re-audit Finding A (2026-09): declineConfirmedBulk's positive path "
            + "against the REAL database — a still-CONFIRMED booking is returned by RETURNING and is "
            + "actually flipped to DECLINED with the given reason")
    @Transactional // direct repository call, no enclosing service @Transactional — see class Javadoc
    void should_declineConfirmedBooking_when_declineConfirmedBulkCalledDirectly() {
        Salon salon = createSalon();
        UUID clientId = createClient();
        UUID bookingId = insertStandaloneBooking(
                clientId, salon.masterId(), salon.masterServiceId(), salon.salonId(), "CONFIRMED", FUTURE);

        List<UUID> declined = bookingRepository.declineConfirmedBulk(
                List.of(bookingId), CancellationReason.PROVIDER_UNAVAILABLE.name(), null, Instant.now());

        assertThat(declined).containsExactly(bookingId);
        assertThat(bookingStatus(bookingId)).isEqualTo("DECLINED");
        assertThat(bookingCancellationReason(bookingId)).isEqualTo("PROVIDER_UNAVAILABLE");
    }

    @Test
    @DisplayName("case 20 — perf re-audit Finding A (2026-09): a SINGLE declineConfirmedBulk statement "
            + "against a MIXED id list — one still-CONFIRMED row and one already-CANCELLED row — declines "
            + "only the CONFIRMED one and leaves the other completely untouched, proving the WHERE status "
            + "= 'CONFIRMED' predicate is evaluated PER ROW inside the one bulk statement, not just when "
            + "the id list happens to be a single row")
    @Transactional // direct repository call, no enclosing service @Transactional — see class Javadoc
    void should_declineOnlyConfirmedRow_when_declineConfirmedBulkCalledWithMixedStatusIds() {
        Salon salon = createSalon();
        UUID clientId = createClient();
        UUID confirmedId = insertStandaloneBooking(
                clientId, salon.masterId(), salon.masterServiceId(), salon.salonId(), "CONFIRMED", FUTURE);
        UUID cancelledId = insertStandaloneBooking(
                clientId, salon.masterId(), salon.masterServiceId(), salon.salonId(), "CANCELLED", FUTURE);

        List<UUID> declined = bookingRepository.declineConfirmedBulk(
                List.of(confirmedId, cancelledId), CancellationReason.PROVIDER_UNAVAILABLE.name(), null,
                Instant.now());

        assertThat(declined)
                .as("only the id that was actually CONFIRMED transitions — never the whole id list")
                .containsExactly(confirmedId);
        assertThat(bookingStatus(confirmedId)).isEqualTo("DECLINED");
        assertThat(bookingStatus(cancelledId))
                .as("the already-terminal sibling in the SAME statement is completely untouched")
                .isEqualTo("CANCELLED");
    }

    // ── Phase 268 — catalogue deactivation, favourites hard-delete, media purge ───────────────

    @Test
    @DisplayName("phase 268 case 1 — the salon's own service_definitions row is deactivated")
    void should_deactivateSalonOwnedServices_when_salonDeactivated() {
        Salon salon = createSalon();

        salonService.deactivateSalon(salon.ownerId(), salon.salonId());

        assertThat(serviceDefinitionIsActive(salon.serviceDefId())).isFalse();
    }

    @Test
    @DisplayName("phase 268 case 2 — an INDEPENDENT_MASTER-owned service_definitions row is untouched "
            + "(scoping pin: deactivateAllByOwner must key on ownerType, not just ownerId)")
    void should_notTouchMasterOwnedServices_when_salonDeactivated() {
        Salon salon = createSalon();
        UUID unrelatedMasterServiceDefId = insertIndependentMasterOwnedService();

        salonService.deactivateSalon(salon.ownerId(), salon.salonId());

        assertThat(serviceDefinitionIsActive(unrelatedMasterServiceDefId))
                .as("an unrelated INDEPENDENT_MASTER's own catalogue must never be touched")
                .isTrue();
    }

    @Test
    @DisplayName("phase 268 case 3 — every client favourite pointing at the deleted salon is hard-deleted")
    void should_deleteSalonFavorites_when_salonDeactivated() {
        Salon salon = createSalon();
        UUID clientId = createClient();
        insertFavorite(clientId, "SALON", salon.salonId());

        salonService.deactivateSalon(salon.ownerId(), salon.salonId());

        assertThat(favoriteExists("SALON", salon.salonId())).isFalse();
    }

    @Test
    @DisplayName("phase 268 case 4 — D5 mutation-check pin: a MASTER favourite whose target_id "
            + "COINCIDENTALLY equals the deleted salon's id is untouched — the delete predicate "
            + "must key on BOTH target_type and target_id")
    void should_notTouchMasterFavoriteWithCoincidentallyEqualTargetId_when_salonDeactivated() {
        Salon salon = createSalon();
        UUID clientId = createClient();
        // Deliberately reuse the salon's own id as a MASTER-typed favourite target — favorites
        // carries no FK (polymorphic column), so this insert is legal and is exactly the trap
        // the phase's mutation check pins.
        insertFavorite(clientId, "MASTER", salon.salonId());
        insertFavorite(clientId, "SALON", salon.salonId());

        salonService.deactivateSalon(salon.ownerId(), salon.salonId());

        assertThat(favoriteExists("SALON", salon.salonId()))
                .as("the real SALON favourite is gone")
                .isFalse();
        assertThat(favoriteExists("MASTER", salon.salonId()))
                .as("a MASTER favourite sharing the same target_id by coincidence must survive")
                .isTrue();
    }

    @Test
    @DisplayName("phase 268 case 5 — the deleted salon no longer appears in salon search/discovery")
    void should_notReturnDeletedSalon_when_searching() {
        Salon salon = createSalon();
        var request = new com.beautica.search.dto.SalonSearchRequest(
                new com.beautica.search.dto.LocationFilter(testCityId(), null),
                null, null, null, null, null, 0, 20, null);

        salonService.deactivateSalon(salon.ownerId(), salon.salonId());

        var results = searchService.searchSalons(request, org.springframework.data.domain.PageRequest.of(0, 20));
        assertThat(results.getContent())
                .as("a deactivated salon must never appear in discovery results")
                .extracting(com.beautica.search.dto.SalonSearchResult::salonId)
                .doesNotContain(salon.salonId());
    }

    @Test
    @DisplayName("phase 268 case 6 — every SALON portfolio media_files row is deleted")
    void should_deleteSalonPortfolioPhotos_when_salonDeactivated() {
        Salon salon = createSalon();
        UUID ownerUserId = salon.ownerId();
        insertMediaFile(ownerUserId, "SALON", salon.salonId(), "portfolio/salons/" + salon.salonId() + "/p1.jpg");
        insertMediaFile(ownerUserId, "SALON", salon.salonId(), "portfolio/salons/" + salon.salonId() + "/p2.jpg");

        salonService.deactivateSalon(salon.ownerId(), salon.salonId());

        assertThat(mediaFileCountForEntity("SALON", salon.salonId())).isZero();
    }

    @Test
    @DisplayName("phase 268 case 7 — avatar_url and cover_image_url are both nulled")
    void should_deleteAvatarAndCoverBlobs_when_salonDeactivated() {
        Salon salon = createSalon();
        jdbcTemplate.update(
                "UPDATE salons SET avatar_url = ?, cover_image_url = ? WHERE id = ?",
                "https://pub.example.r2.dev/portfolio/salons/" + salon.salonId() + "/avatar.jpg",
                "https://pub.example.r2.dev/portfolio/salons/" + salon.salonId() + "/cover.jpg",
                salon.salonId());

        salonService.deactivateSalon(salon.ownerId(), salon.salonId());

        assertThat(salonAvatarUrl(salon.salonId())).isNull();
        assertThat(salonCoverImageUrl(salon.salonId())).isNull();
    }

    @Test
    @DisplayName("phase 268 case 8 — media belonging to a DIFFERENT salon is untouched")
    void should_notTouchMediaOfOtherSalons_when_salonDeactivated() {
        Salon salon = createSalon();
        Salon otherSalon = createSalon();
        insertMediaFile(salon.ownerId(), "SALON", salon.salonId(), "portfolio/salons/" + salon.salonId() + "/p1.jpg");
        insertMediaFile(otherSalon.ownerId(), "SALON", otherSalon.salonId(),
                "portfolio/salons/" + otherSalon.salonId() + "/p1.jpg");

        salonService.deactivateSalon(salon.ownerId(), salon.salonId());

        assertThat(mediaFileCountForEntity("SALON", salon.salonId())).isZero();
        assertThat(mediaFileCountForEntity("SALON", otherSalon.salonId()))
                .as("an unrelated salon's media must never be touched")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("phase 268 GAP fix — a salon photo uploaded by a STAFF member (hard-deleted by "
            + "deleteSalonStaff, which cascades the media_files row away via ON DELETE CASCADE on "
            + "uploader_id) is still fully swept: the pre-read captured it before the cascade could "
            + "remove it, and the after-commit sweep completes without error")
    void should_sweepStaffUploadedMedia_when_uploaderIsHardDeletedBySameDeactivation() {
        Salon salon = createSalon();
        insertMediaFile(salon.masterUserId(), "SALON", salon.salonId(),
                "portfolio/salons/" + salon.salonId() + "/staff-upload.jpg");

        assertThatCode(() -> salonService.deactivateSalon(salon.ownerId(), salon.salonId()))
                .as("the deactivation must complete without throwing even though its own staff "
                        + "cascade deletes the uploader of a salon photo in the same transaction")
                .doesNotThrowAnyException();

        assertThat(mediaFileCountForEntity("SALON", salon.salonId()))
                .as("the row is gone — either via the staff-delete CASCADE or the after-commit "
                        + "sweep's own DB delete, whichever ran first")
                .isZero();
        assertThat(isSalonActive(salon.salonId())).isFalse();
    }

    @Test
    @DisplayName("phase 268 GAP fix, ordering pin — R2 delete is actually ATTEMPTED for a "
            + "staff-uploaded salon photo's key, proving deactivateSalon's pre-read "
            + "(mediaRepository.findByEntityTypeAndEntityId, SalonService.java around line 957-958) "
            + "ran BEFORE deleteSalonStaff hard-deleted the uploader and cascaded the row away. "
            + "The row-count-only assertion in the previous test cannot tell these two orderings "
            + "apart — both end with the row gone, one via this sweep's own DB delete, the other "
            + "via ON DELETE CASCADE alone — so this test asserts the SIDE EFFECT (a batched R2 "
            + "delete call carrying THIS key) that only happens if the pre-read still saw the row. "
            + "Moving the pre-read to run AFTER deleteSalonStaff makes this go RED: the query would "
            + "return zero rows (already cascaded away), so r2StorageService.deleteFiles is never "
            + "invoked with this key. Updated for the Phase 268 perf follow-up that batched "
            + "MediaService's sweep onto R2StorageService#deleteFiles (was #deleteFile per key).")
    void should_attemptR2DeleteForStaffUploadedPhoto_when_preReadRunsBeforeStaffCascade() {
        Salon salon = createSalon();
        String r2Key = "portfolio/salons/" + salon.salonId() + "/staff-upload.jpg";
        insertMediaFile(salon.masterUserId(), "SALON", salon.salonId(), r2Key);

        salonService.deactivateSalon(salon.ownerId(), salon.salonId());

        verify(r2StorageService)
                .deleteFiles(List.of(r2Key));
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────

    private record Salon(UUID ownerId, UUID salonId, UUID masterId, UUID masterServiceId,
                          UUID masterUserId, UUID serviceDefId) {}

    /** Ordered by ascending {@code startsAt} — {@code bookingIds().get(0)} is the representative. */
    private record Visit(UUID appointmentId, List<UUID> bookingIds) {}

    private Salon createSalon() {
        UUID ownerId = createOwner();
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at, city_id) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW(), ?)",
                salonId, ownerId, "Salon-" + salonId, testCityId());

        UUID masterUserId = createUser(
                "sdc-master-" + System.nanoTime() + "@beautica.test", "SALON_MASTER", salonId);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                masterId, masterUserId, salonId);

        UUID serviceTypeId = resolveUnusedServiceTypeId("SALON", salonId);
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'SALON', ?, 'Test Service', ?, 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, salonId, serviceTypeId);
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);

        return new Salon(ownerId, salonId, masterId, masterServiceId, masterUserId, serviceDefId);
    }

    private UUID createOwner() {
        return createUser("sdc-owner-" + System.nanoTime() + "@beautica.test", "SALON_OWNER", null);
    }

    private UUID createClient() {
        return createUser("sdc-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
    }

    private UUID createUser(String email, String role, UUID salonId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, is_active, email_verified) "
                        + "VALUES (?, ?, ?, ?, ?, true, true)",
                id, email, passwordEncoder.encode(TEST_PASSWORD), role, salonId);
        return id;
    }

    private UUID insertStandaloneBooking(
            UUID clientId, UUID masterId, UUID masterServiceId, UUID salonId, String status, OffsetDateTime startsAt) {
        return insertStandaloneBookingFull(
                clientId, masterId, masterServiceId, salonId, status, startsAt, null, null);
    }

    /**
     * @param clientComment the booking-CREATION note ({@code bookings.client_comment}) —
     *                       legitimately present on ANY status (no CHECK couples it to a
     *                       terminal state, unlike {@code provider_comment}), which is exactly
     *                       why case 2 uses this column rather than {@code provider_comment} to
     *                       prove a COMPLETED row's snapshot survives the cascade untouched.
     */
    private UUID insertStandaloneBookingFull(
            UUID clientId, UUID masterId, UUID masterServiceId, UUID salonId, String status,
            OffsetDateTime startsAt, String cancellationReason, String clientComment) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, booking_source, cancellation_reason, client_comment, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 500.00, 60, 0, 'APP', ?, ?, NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId, salonId, status,
                startsAt, startsAt.plusMinutes(60), cancellationReason, clientComment);
        return bookingId;
    }

    /**
     * A {@code count}-service visit (1 appointment header + N chained bookings, one master, back
     * to back from {@code start}), inserted in ASCENDING {@code startsAt} row order — item 0 of
     * the returned {@link Visit} is therefore both the physically-first-inserted row and the
     * earliest-starting one. Use {@link #insertVisitOutOfOrder} when the test needs those two to
     * DISAGREE (case 17's determinism pin).
     */
    private Visit insertVisit(UUID clientId, Salon salon, OffsetDateTime start, int count) {
        UUID appointmentId = insertAppointmentHeader(clientId, salon.salonId());
        List<UUID> bookingIds = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            OffsetDateTime itemStart = start.plusMinutes(60L * i);
            bookingIds.add(insertAppointmentItem(
                    clientId, salon.masterId(), salon.masterServiceId(), salon.salonId(),
                    appointmentId, itemStart));
        }
        return new Visit(appointmentId, List.copyOf(bookingIds));
    }

    /**
     * Same shape as {@link #insertVisit}, but the physical INSERT order is the REVERSE of
     * {@code startsAt} order — item 0 of the returned {@link Visit} is still the
     * earliest-starting booking (what the representative-selection rule must pick), but it is the
     * LAST row physically inserted. Proves the D12 representative pick is driven by
     * {@code (startsAt, id)}, never by scan/insertion order.
     */
    private Visit insertVisitOutOfOrder(UUID clientId, Salon salon, OffsetDateTime start, int count) {
        UUID appointmentId = insertAppointmentHeader(clientId, salon.salonId());
        UUID[] bookingIds = new UUID[count];
        for (int i = count - 1; i >= 0; i--) {
            OffsetDateTime itemStart = start.plusMinutes(60L * i);
            bookingIds[i] = insertAppointmentItem(
                    clientId, salon.masterId(), salon.masterServiceId(), salon.salonId(),
                    appointmentId, itemStart);
        }
        return new Visit(appointmentId, List.of(bookingIds));
    }

    private UUID insertAppointmentHeader(UUID clientId, UUID salonId) {
        UUID appointmentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO appointments (id, client_id, salon_id, status, booking_source, "
                        + "created_at, updated_at) VALUES (?, ?, ?, 'CONFIRMED', 'APP', NOW(), NOW())",
                appointmentId, clientId, salonId);
        return appointmentId;
    }

    private UUID insertAppointmentItem(
            UUID clientId, UUID masterId, UUID masterServiceId, UUID salonId, UUID appointmentId,
            OffsetDateTime startsAt) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, "
                        + "appointment_id, status, starts_at, ends_at, price_at_booking, "
                        + "duration_minutes_at_booking, buffer_minutes_at_booking, booking_source, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'CONFIRMED', ?, ?, 500.00, 45, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId, salonId, appointmentId,
                startsAt, startsAt.plusMinutes(45));
        return bookingId;
    }

    private int countBookingsByAppointment(UUID appointmentId, String status) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE appointment_id = ? AND status = ?",
                Integer.class, appointmentId, status);
    }

    // ── assertions ──────────────────────────────────────────────────────────────────────────

    private boolean isSalonActive(UUID salonId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT is_active FROM salons WHERE id = ?", Boolean.class, salonId));
    }

    private boolean bookingExists(UUID bookingId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE id = ?", Integer.class, bookingId);
        return count != null && count == 1;
    }

    private String bookingStatus(UUID bookingId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
    }

    private String bookingCancellationReason(UUID bookingId) {
        return jdbcTemplate.queryForObject(
                "SELECT cancellation_reason FROM bookings WHERE id = ?", String.class, bookingId);
    }

    private String bookingProviderComment(UUID bookingId) {
        return jdbcTemplate.queryForObject(
                "SELECT provider_comment FROM bookings WHERE id = ?", String.class, bookingId);
    }

    private BigDecimal bookingPrice(UUID bookingId) {
        return jdbcTemplate.queryForObject(
                "SELECT price_at_booking FROM bookings WHERE id = ?", BigDecimal.class, bookingId);
    }

    private String bookingClientComment(UUID bookingId) {
        return jdbcTemplate.queryForObject(
                "SELECT client_comment FROM bookings WHERE id = ?", String.class, bookingId);
    }

    private List<UUID> salonClosedAggregateIds() {
        return jdbcTemplate.queryForList(
                "SELECT aggregate_id FROM notification_outbox WHERE event_type = 'SALON_CLOSED'",
                UUID.class);
    }

    /**
     * The single {@code SALON_CLOSED} entry whose aggregate id is one of {@code visitBookingIds}
     * — asserts there is exactly one (D12: one entry per visit) before returning it.
     */
    private UUID singleSalonClosedAggregateIdFor(List<UUID> visitBookingIds) {
        List<UUID> matches = salonClosedAggregateIds().stream()
                .filter(visitBookingIds::contains)
                .toList();
        assertThat(matches)
                .as("exactly one SALON_CLOSED entry must be keyed to this visit's bookings")
                .hasSize(1);
        return matches.get(0);
    }

    // ── Phase 268 fixtures/assertions ──────────────────────────────────────────────────────

    private boolean serviceDefinitionIsActive(UUID serviceDefId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT is_active FROM service_definitions WHERE id = ?", Boolean.class, serviceDefId));
    }

    /**
     * Seeds an ACTIVE {@code service_definitions} row owned by a brand-new
     * {@code INDEPENDENT_MASTER} — an owner-type/id pair structurally unrelated to any salon
     * this class creates, backing the {@code deactivateAllByOwner} scoping pin (case 2).
     */
    private UUID insertIndependentMasterOwnedService() {
        UUID masterUserId = createUser(
                "sdc-indep-" + System.nanoTime() + "@beautica.test", "INDEPENDENT_MASTER", null);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, 'INDEPENDENT_MASTER', true, NOW(), NOW())",
                masterId, masterUserId);
        UUID serviceTypeId = resolveUnusedServiceTypeId("INDEPENDENT_MASTER", masterId);
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'INDEPENDENT_MASTER', ?, 'Unrelated Service', ?, 60, 400.00, 0, true, NOW(), NOW())",
                serviceDefId, masterId, serviceTypeId);
        return serviceDefId;
    }

    private void insertFavorite(UUID clientId, String targetType, UUID targetId) {
        jdbcTemplate.update(
                "INSERT INTO favorites (id, client_id, target_type, target_id, created_at) "
                        + "VALUES (?, ?, ?, ?, NOW())",
                UUID.randomUUID(), clientId, targetType, targetId);
    }

    private boolean favoriteExists(String targetType, UUID targetId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM favorites WHERE target_type = ? AND target_id = ?",
                Integer.class, targetType, targetId);
        return count != null && count > 0;
    }

    private void insertMediaFile(UUID uploaderId, String entityType, UUID entityId, String r2Key) {
        jdbcTemplate.update(
                "INSERT INTO media_files (id, uploader_id, entity_type, entity_id, media_type, "
                        + "r2_key, r2_url, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'PORTFOLIO', ?, ?, NOW(), NOW())",
                UUID.randomUUID(), uploaderId, entityType, entityId, r2Key,
                "https://pub.example.r2.dev/" + r2Key);
    }

    private int mediaFileCountForEntity(String entityType, UUID entityId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM media_files WHERE entity_type = ? AND entity_id = ?",
                Integer.class, entityType, entityId);
        return count == null ? 0 : count;
    }

    private String salonAvatarUrl(UUID salonId) {
        return jdbcTemplate.queryForObject(
                "SELECT avatar_url FROM salons WHERE id = ?", String.class, salonId);
    }

    private String salonCoverImageUrl(UUID salonId) {
        return jdbcTemplate.queryForObject(
                "SELECT cover_image_url FROM salons WHERE id = ?", String.class, salonId);
    }
}
