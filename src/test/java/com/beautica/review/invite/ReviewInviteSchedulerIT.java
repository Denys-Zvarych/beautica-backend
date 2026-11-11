package com.beautica.review.invite;

import com.beautica.booking.event.GuestBookingCompletedEvent;
import com.beautica.booking.event.GuestVisitCompletedEvent;
import com.beautica.booking.service.AppointmentTransitionService;
import com.beautica.booking.service.BookingService;
import com.beautica.common.security.PhoneHasher;
import com.beautica.review.invite.config.ReviewInviteProperties;
import com.beautica.review.invite.entity.SmsOptOut;
import com.beautica.review.invite.entity.SmsOptOutSource;
import com.beautica.review.invite.repository.SmsOptOutRepository;
import com.beautica.review.invite.service.QuietHoursCalculator;
import com.beautica.review.invite.service.ReviewInviteScheduler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 400 D1/D2 — invite creation through the real completion paths ({@code AppointmentTransitionService} →
 * {@code GuestVisitCompletedEvent} → AFTER_COMMIT {@code ReviewInviteScheduler}), both flags on.
 */
@TestPropertySource(properties = {
        "app.frontend.base-url=https://beautica.app",
        "app.booking.sms.enabled=true",
        "app.booking.review-invite.enabled=true",
        "app.booking.review-invite.min-policy-version=2026-10-20"
})
@DisplayName("ReviewInviteScheduler — phase 400 IT")
class ReviewInviteSchedulerIT extends AbstractReviewInviteIT {

    @Autowired
    private AppointmentTransitionService appointmentTransitionService;

    @Autowired
    private BookingService bookingService;

    @Autowired
    private ReviewInviteScheduler reviewInviteScheduler;

    @Autowired
    private QuietHoursCalculator quietHours;

    @Autowired
    private SmsOptOutRepository smsOptOutRepository;

    @Autowired
    private PhoneHasher phoneHasher;

    @Test
    @DisplayName("item-level completion then the remaining item → exactly ONE SCHEDULED invite, send_after from the "
            + "first completion, keyed by the PhoneHasher hash, no token yet")
    void should_createOneInvite_when_itemThenHeaderCompleted() {
        UUID appointmentId = fixtures.linkVisit("CONFIRMED", GUEST_PHONE, POLICY);
        UUID first = fixtures.visitItem(appointmentId, masterId, masterServiceId, "LINK", "CONFIRMED", GUEST_PHONE, 3);
        fixtures.visitItem(appointmentId, masterId, masterServiceId, "LINK", "CONFIRMED", GUEST_PHONE, 2);
        Instant before = Instant.now();

        authenticateAsMaster();
        appointmentTransitionService.completeAppointmentItem(masterUserId, appointmentId, first);
        Instant after = Instant.now();
        completeVisitItems(appointmentId);

        assertThat(inviteCount()).isEqualTo(1);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM review_invites WHERE appointment_id = ?", appointmentId);
        assertThat(row.get("status")).isEqualTo("SCHEDULED");
        assertThat(row.get("token_hash")).isNull();
        assertThat((byte[]) row.get("phone_hash")).isEqualTo(phoneHasher.hash(GUEST_PHONE).hash());
        Instant sendAfter = ((Timestamp) row.get("send_after")).toInstant();
        assertThat(sendAfter).isBetween(quietHours.sendAfter(before), quietHours.sendAfter(after));
        assertThat(turbosmsRequests()).as("creation never sends").isEmpty();
    }

    @Test
    @DisplayName("completing every item of a LINK visit → one SCHEDULED invite")
    void should_createInvite_when_wholeVisitCompleted() {
        UUID appointmentId = confirmedLinkVisit(GUEST_PHONE, POLICY);

        authenticateAsMaster();
        completeVisitItems(appointmentId);

        assertThat(statusOf(appointmentId)).isEqualTo("SCHEDULED");
    }

    @Test
    @DisplayName("phase 434 — a STAFF walk-in visit for an unregistered guest (no policy version) → one SCHEDULED invite")
    void should_createInvite_when_staffWalkInVisitCompleted() {
        UUID appointmentId = fixtures.staffGuestVisit("CONFIRMED", GUEST_PHONE);
        fixtures.visitItem(appointmentId, masterId, masterServiceId, "STAFF", "CONFIRMED", GUEST_PHONE, 3);

        authenticateAsMaster();
        completeVisitItems(appointmentId);

        assertThat(inviteCount()).isEqualTo(1);
        assertThat(statusOf(appointmentId)).isEqualTo("SCHEDULED");
    }

    @Test
    @DisplayName("phase 434 — item-level completion of a STAFF walk-in visit (visit stays open) → one SCHEDULED invite")
    void should_createInvite_when_staffWalkInVisitItemCompleted() {
        UUID appointmentId = fixtures.staffGuestVisit("CONFIRMED", GUEST_PHONE);
        UUID first = fixtures.visitItem(appointmentId, masterId, masterServiceId, "STAFF", "CONFIRMED", GUEST_PHONE, 3);
        fixtures.visitItem(appointmentId, masterId, masterServiceId, "STAFF", "CONFIRMED", GUEST_PHONE, 2);

        authenticateAsMaster();
        appointmentTransitionService.completeAppointmentItem(masterUserId, appointmentId, first);

        assertThat(inviteCount()).isEqualTo(1);
        assertThat(statusOf(appointmentId)).isEqualTo("SCHEDULED");
        assertThat(turbosmsRequests()).as("creation never sends").isEmpty();
    }

    @Test
    @DisplayName("phase 434 — completing every item of an APP visit and of a STAFF visit WITH an account → no invite")
    void should_createNoInvite_when_appVisitOrStaffVisitWithClientCompleted() {
        UUID clientId = fixtures.user("CLIENT", "Олена", "Бондар");
        UUID appVisit = confirmedClientVisit(clientId, "APP", 5);
        UUID staffVisit = confirmedClientVisit(clientId, "STAFF", 3);

        authenticateAsMaster();
        completeVisitItems(appVisit);
        completeVisitItems(staffVisit);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE appointment_id IN (?, ?) AND status = 'COMPLETED'",
                Long.class, appVisit, staffVisit)).as("both visits really completed").isEqualTo(2L);
        assertThat(inviteCount()).isZero();
    }

    @Test
    @DisplayName("phase 434 — completing a single STAFF walk-in booking (no account, no policy version) → ONE "
            + "booking-keyed SCHEDULED invite keyed by the walk-in phone")
    void should_createBookingKeyedInvite_when_singleStaffWalkInBookingCompleted() {
        UUID bookingId = fixtures.visitItem(null, masterId, masterServiceId, "STAFF", "CONFIRMED", GUEST_PHONE, 2);

        authenticateAsMaster();
        bookingService.completeBooking(masterUserId, bookingId);

        assertThat(inviteCount()).isEqualTo(1);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM review_invites WHERE booking_id = ?", bookingId);
        assertThat(row.get("appointment_id")).isNull();
        assertThat(row.get("status")).isEqualTo("SCHEDULED");
        assertThat(row.get("skip_reason")).isNull();
        assertThat((byte[]) row.get("phone_hash")).isEqualTo(phoneHasher.hash(GUEST_PHONE).hash());
        assertThat(turbosmsRequests()).as("creation never sends").isEmpty();
    }

    @Test
    @DisplayName("an opted-out phone → the row is SKIPPED (OPTED_OUT) from birth")
    void should_createSkippedInvite_when_phoneOptedOut() {
        smsOptOutRepository.saveAndFlush(
                SmsOptOut.of(phoneHasher.hash(GUEST_PHONE), SmsOptOutSource.REVIEW_INVITE, Instant.now()));
        UUID appointmentId = confirmedLinkVisit(GUEST_PHONE, POLICY);

        authenticateAsMaster();
        completeVisitItems(appointmentId);

        assertThat(statusOf(appointmentId)).isEqualTo("SKIPPED");
        assertThat(skipReasonOf(appointmentId)).isEqualTo("OPTED_OUT");
    }

    @Test
    @DisplayName("a guest who accepted an older privacy policy → SKIPPED (POLICY_VERSION)")
    void should_createSkippedInvite_when_policyVersionOlder() {
        UUID appointmentId = confirmedLinkVisit(GUEST_PHONE, "2026-10-19");

        authenticateAsMaster();
        completeVisitItems(appointmentId);

        assertThat(skipReasonOf(appointmentId)).isEqualTo("POLICY_VERSION");
    }

    // ── Phase 400 D7 — single LINK booking (appointment_id NULL) via BookingService#completeBooking ──

    @Test
    @DisplayName("D7 — completing a single LINK guest booking → exactly ONE booking-keyed SCHEDULED invite, "
            + "send_after from the quiet-hours rule")
    void should_createBookingKeyedInvite_when_singleLinkBookingCompleted() {
        UUID bookingId = fixtures.singleLinkBooking(masterId, masterServiceId, "CONFIRMED", GUEST_PHONE, POLICY, 2);
        Instant before = Instant.now();

        authenticateAsMaster();
        bookingService.completeBooking(masterUserId, bookingId);
        Instant after = Instant.now();

        assertThat(inviteCount()).isEqualTo(1);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM review_invites WHERE booking_id = ?", bookingId);
        assertThat(row.get("appointment_id")).isNull();
        assertThat(row.get("status")).isEqualTo("SCHEDULED");
        assertThat(row.get("token_hash")).isNull();
        assertThat((byte[]) row.get("phone_hash")).isEqualTo(phoneHasher.hash(GUEST_PHONE).hash());
        Instant sendAfter = ((Timestamp) row.get("send_after")).toInstant();
        assertThat(sendAfter).isBetween(quietHours.sendAfter(before), quietHours.sendAfter(after));
        assertThat(turbosmsRequests()).as("creation never sends").isEmpty();
    }

    @Test
    @DisplayName("D7 — a replayed GuestBookingCompletedEvent still leaves ONE invite")
    void should_keepOneInvite_when_bookingEventReplayed() {
        UUID bookingId = fixtures.singleLinkBooking(masterId, masterServiceId, "CONFIRMED", GUEST_PHONE, POLICY, 2);
        authenticateAsMaster();
        bookingService.completeBooking(masterUserId, bookingId);
        Object firstId = jdbcTemplate.queryForObject(
                "SELECT id FROM review_invites WHERE booking_id = ?", UUID.class, bookingId);

        reviewInviteScheduler.onGuestBookingCompleted(new GuestBookingCompletedEvent(bookingId, Instant.now()));

        assertThat(inviteCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT id FROM review_invites WHERE booking_id = ?", UUID.class, bookingId)).isEqualTo(firstId);
    }

    @Test
    @DisplayName("D7 — single APP and STAFF bookings never get an invite")
    void should_createNoInvite_when_singleAppOrStaffBookingCompleted() {
        UUID clientId = fixtures.user("CLIENT", "Олена", "Бондар");
        UUID appBooking = fixtures.singleClientBooking(clientId, masterId, masterServiceId, "APP", "CONFIRMED", 4);
        UUID staffBooking = fixtures.singleClientBooking(clientId, masterId, masterServiceId, "STAFF", "CONFIRMED",
                2);

        authenticateAsMaster();
        bookingService.completeBooking(masterUserId, appBooking);
        bookingService.completeBooking(masterUserId, staffBooking);

        assertThat(inviteCount()).isZero();
    }

    @Test
    @DisplayName("D7 — an opted-out phone → the booking-keyed row is SKIPPED (OPTED_OUT) from birth")
    void should_createSkippedBookingInvite_when_phoneOptedOut() {
        smsOptOutRepository.saveAndFlush(
                SmsOptOut.of(phoneHasher.hash(GUEST_PHONE), SmsOptOutSource.REVIEW_INVITE, Instant.now()));
        UUID bookingId = fixtures.singleLinkBooking(masterId, masterServiceId, "CONFIRMED", GUEST_PHONE, POLICY, 2);

        authenticateAsMaster();
        bookingService.completeBooking(masterUserId, bookingId);

        assertThat(bookingInviteColumn(bookingId, "status")).isEqualTo("SKIPPED");
        assertThat(bookingInviteColumn(bookingId, "skip_reason")).isEqualTo("OPTED_OUT");
    }

    @Test
    @DisplayName("D7 — a policy version below the minimum → booking-keyed row SKIPPED (POLICY_VERSION)")
    void should_createSkippedBookingInvite_when_policyVersionOlder() {
        UUID bookingId = fixtures.singleLinkBooking(masterId, masterServiceId, "CONFIRMED", GUEST_PHONE,
                "2026-10-19", 2);

        authenticateAsMaster();
        bookingService.completeBooking(masterUserId, bookingId);

        assertThat(bookingInviteColumn(bookingId, "skip_reason")).isEqualTo("POLICY_VERSION");
    }

    @Test
    @DisplayName("D7 — an anonymised single LINK booking gets no row")
    void should_createNoInvite_when_bookingAnonymised() {
        UUID bookingId = fixtures.singleLinkBooking(masterId, masterServiceId, "COMPLETED", GUEST_PHONE, POLICY, 5);
        fixtures.anonymiseBooking(bookingId);

        reviewInviteScheduler.onGuestBookingCompleted(new GuestBookingCompletedEvent(bookingId, Instant.now()));

        assertThat(inviteCount()).isZero();
    }

    // ── Phase 434 audit-fix (sec M1/M2) — STAFF walk-in phone guards ──

    @Test
    @DisplayName("434 audit-fix M1 — a second STAFF walk-in on the same phone for the same master within the window "
            + "→ SKIPPED (REPEAT_PHONE); the first stays SCHEDULED")
    void should_skipInvite_when_samePhoneInvitedSameProviderWithinWindow() {
        UUID first = completedStaffWalkIn(masterId, masterServiceId, GUEST_PHONE, 8);
        UUID second = completedStaffWalkIn(masterId, masterServiceId, GUEST_PHONE, 4);

        fireVisitCompleted(first);
        fireVisitCompleted(second);

        assertThat(statusOf(first)).isEqualTo("SCHEDULED");
        assertThat(statusOf(second)).isEqualTo("SKIPPED");
        assertThat(skipReasonOf(second)).isEqualTo("REPEAT_PHONE");
        assertThat(logLines()).noneMatch(line -> line.contains(GUEST_SUBSCRIBER_DIGITS));
    }

    @Test
    @DisplayName("434 audit-fix M1 — same phone, a DIFFERENT master → still invited")
    void should_inviteStaffWalkIn_when_samePhoneDifferentProvider() {
        UUID otherMasterId = fixtures.independentMaster();
        UUID otherServiceId = fixtures.masterService(otherMasterId);
        UUID first = completedStaffWalkIn(masterId, masterServiceId, GUEST_PHONE, 8);
        UUID second = completedStaffWalkIn(otherMasterId, otherServiceId, GUEST_PHONE, 4);

        fireVisitCompleted(first);
        fireVisitCompleted(second);

        assertThat(statusOf(first)).isEqualTo("SCHEDULED");
        assertThat(statusOf(second)).isEqualTo("SCHEDULED");
    }

    @Test
    @DisplayName("434 audit-fix M1 — the prior invite for the same phone + master is older than the window → invited")
    void should_inviteStaffWalkIn_when_priorInviteOutsideWindow() {
        UUID old = completedStaffWalkIn(masterId, masterServiceId, GUEST_PHONE, 8);
        scheduledInvite(old, phoneHasher.hash(GUEST_PHONE).hash(), Instant.now().minus(Duration.ofDays(31)));
        UUID fresh = completedStaffWalkIn(masterId, masterServiceId, GUEST_PHONE, 4);

        fireVisitCompleted(fresh);

        assertThat(statusOf(fresh)).isEqualTo("SCHEDULED");
    }

    @Test
    @DisplayName("434 audit-fix M2 — a STAFF walk-in on the performing master's own account phone (other format) "
            + "→ SKIPPED (PROVIDER_OWN_PHONE)")
    void should_skipInvite_when_walkInPhoneIsMasterOwnPhone() {
        jdbcTemplate.update("UPDATE users SET phone_number = '050 222 33 44' WHERE id = ?", masterUserId);
        UUID appointmentId = completedStaffWalkIn(masterId, masterServiceId, GUEST_PHONE, 4);

        fireVisitCompleted(appointmentId);

        assertThat(skipReasonOf(appointmentId)).isEqualTo("PROVIDER_OWN_PHONE");
    }

    @Test
    @DisplayName("434 audit-fix M2 — a salon STAFF walk-in on the salon owner's phone → SKIPPED (PROVIDER_OWN_PHONE)")
    void should_skipInvite_when_walkInPhoneIsSalonOwnerPhone() {
        UUID salonId = affiliateMasterWithSalon();
        jdbcTemplate.update("UPDATE users SET phone_number = ? WHERE id = ?", GUEST_PHONE, salonOwnerId(salonId));
        UUID appointmentId = fixtures.staffGuestVisit("COMPLETED", GUEST_PHONE);
        UUID item = fixtures.visitItem(appointmentId, masterId, masterServiceId, "STAFF", "COMPLETED", GUEST_PHONE, 4);
        attachToSalon(salonId, item);

        fireVisitCompleted(appointmentId);

        assertThat(skipReasonOf(appointmentId)).isEqualTo("PROVIDER_OWN_PHONE");
    }

    @Test
    @DisplayName("434 audit-fix — LINK is unaffected: a LINK visit on a phone already STAFF-invited for the same master, "
            + "and on the master's own phone, is still SCHEDULED")
    void should_inviteLinkVisit_when_phoneAlreadyStaffInvitedOrProviderOwn() {
        jdbcTemplate.update("UPDATE users SET phone_number = ? WHERE id = ?", GUEST_PHONE, masterUserId);
        UUID walkIn = completedStaffWalkIn(masterId, masterServiceId, GUEST_PHONE, 8);
        scheduledInvite(walkIn, phoneHasher.hash(GUEST_PHONE).hash(), Instant.now().minus(Duration.ofDays(1)));
        UUID link = completedLinkVisit(GUEST_PHONE, POLICY);

        fireVisitCompleted(link);

        assertThat(statusOf(link)).isEqualTo("SCHEDULED");
    }

    @Test
    @DisplayName("434 audit-fix LOW-1 — a salon STAFF walk-in on a COLLEAGUE salon master's phone (not the performing "
            + "master) → SKIPPED (PROVIDER_OWN_PHONE)")
    void should_skipProviderOwnPhone_when_walkInPhoneIsColleagueSalonMaster() {
        UUID salonId = affiliateMasterWithSalon();
        UUID colleagueUserId = fixtures.user("SALON_MASTER", "Оксана", "Колега");
        jdbcTemplate.update("UPDATE users SET phone_number = '050 222 33 44', salon_id = ? WHERE id = ?", salonId,
                colleagueUserId);
        jdbcTemplate.update("INSERT INTO masters (id, user_id, master_type, salon_id, is_active, created_at, updated_at) "
                + "VALUES (?, ?, 'SALON_MASTER', ?, true, NOW(), NOW())", UUID.randomUUID(), colleagueUserId, salonId);
        UUID appointmentId = fixtures.staffGuestVisit("COMPLETED", GUEST_PHONE);
        UUID item = fixtures.visitItem(appointmentId, masterId, masterServiceId, "STAFF", "COMPLETED", GUEST_PHONE, 4);
        attachToSalon(salonId, item);

        fireVisitCompleted(appointmentId);

        assertThat(skipReasonOf(appointmentId)).isEqualTo("PROVIDER_OWN_PHONE");
        assertThat(logLines()).noneMatch(line -> line.contains(GUEST_SUBSCRIBER_DIGITS));
    }

    @Test
    @DisplayName("434 audit-fix LOW-4 — the phone already has 3 non-skipped STAFF invites at 3 OTHER masters within "
            + "the window → the 4th provider's walk-in is SKIPPED (PHONE_GLOBAL_CAP); the first 3 stay SCHEDULED")
    void should_skipPhoneGlobalCap_when_phoneHasThreeStaffInvitesAcrossProviders() {
        UUID[] prior = new UUID[3];
        for (int i = 0; i < prior.length; i++) {
            UUID otherMaster = fixtures.independentMaster();
            prior[i] = completedStaffWalkIn(otherMaster, fixtures.masterService(otherMaster), GUEST_PHONE, 8);
            fireVisitCompleted(prior[i]);
        }
        UUID fourth = completedStaffWalkIn(masterId, masterServiceId, GUEST_PHONE, 4);

        fireVisitCompleted(fourth);

        for (UUID appointmentId : prior) {
            assertThat(statusOf(appointmentId)).isEqualTo("SCHEDULED");
        }
        assertThat(skipReasonOf(fourth)).isEqualTo("PHONE_GLOBAL_CAP");
        assertThat(logLines()).noneMatch(line -> line.contains(GUEST_SUBSCRIBER_DIGITS));
    }

    @Test
    @DisplayName("434 audit-fix LOW-4 — the master already created 50 non-skipped STAFF invites (distinct phones) in "
            + "the last 24 h → the next walk-in is SKIPPED (PROVIDER_DAILY_CAP)")
    void should_skipProviderDailyCap_when_providerCreatedFiftyStaffInvitesToday() {
        seedRecentStaffInvitesForMaster(ReviewInviteProperties.DEFAULT_STAFF_PROVIDER_DAILY_CAP);
        UUID next = completedStaffWalkIn(masterId, masterServiceId, GUEST_PHONE, 1);

        fireVisitCompleted(next);

        assertThat(skipReasonOf(next)).isEqualTo("PROVIDER_DAILY_CAP");
    }

    @Test
    @DisplayName("434 audit-fix LOW-4 — boundary: the master has 49 (cap − 1) non-skipped STAFF invites in the last "
            + "24 h → the next walk-in is still SCHEDULED, no skip reason")
    void should_inviteStaffWalkIn_when_providerOneBelowDailyCap() {
        seedRecentStaffInvitesForMaster(ReviewInviteProperties.DEFAULT_STAFF_PROVIDER_DAILY_CAP - 1);
        UUID next = completedStaffWalkIn(masterId, masterServiceId, GUEST_PHONE, 1);

        fireVisitCompleted(next);

        assertThat(statusOf(next)).as("49 prior invites is below the cap of 50").isEqualTo("SCHEDULED");
        assertThat(skipReasonOf(next)).isNull();
    }

    /** {@code count} SCHEDULED STAFF invites for {@link #masterId}, distinct phones, created an hour ago. */
    private void seedRecentStaffInvitesForMaster(int count) {
        Instant anHourAgo = Instant.now().minus(Duration.ofHours(1));
        for (int i = 0; i < count; i++) {
            String phone = String.format("+38067%07d", i);
            UUID visit = completedStaffWalkIn(masterId, masterServiceId, phone, 2 + i);
            scheduledInvite(visit, phoneHasher.hash(phone).hash(), anHourAgo);
        }
    }

    private void fireVisitCompleted(UUID appointmentId) {
        reviewInviteScheduler.onGuestVisitCompleted(new GuestVisitCompletedEvent(appointmentId, Instant.now()));
    }

    private String bookingInviteColumn(UUID bookingId, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM review_invites WHERE booking_id = ?", String.class, bookingId);
    }

    /** A CONFIRMED one-item {@code source} visit (APP or STAFF) of the registered {@code clientId}. */
    private UUID confirmedClientVisit(UUID clientId, String source, int hoursAgo) {
        UUID appointmentId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO appointments (id, client_id, status, booking_source, created_at, updated_at) "
                + "VALUES (?, ?, 'CONFIRMED', ?, NOW(), NOW())", appointmentId, clientId, source);
        jdbcTemplate.update("INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, appointment_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, NULL, 'CONFIRMED', NOW() - make_interval(hours => ?), "
                        + "NOW() - make_interval(hours => ?) + interval '60 minutes', 500.00, 60, 0, ?, ?, NOW(), NOW())",
                UUID.randomUUID(), clientId, masterId, masterServiceId, hoursAgo, hoursAgo, source, appointmentId);
        return appointmentId;
    }

    private UUID confirmedLinkVisit(String phone, String policyVersion) {
        UUID appointmentId = fixtures.linkVisit("CONFIRMED", phone, policyVersion);
        fixtures.visitItem(appointmentId, masterId, masterServiceId, "LINK", "CONFIRMED", phone, 3);
        return appointmentId;
    }

    private String statusOf(UUID appointmentId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM review_invites WHERE appointment_id = ?", String.class, appointmentId);
    }

    private String skipReasonOf(UUID appointmentId) {
        return jdbcTemplate.queryForObject(
                "SELECT skip_reason FROM review_invites WHERE appointment_id = ?", String.class, appointmentId);
    }
}
