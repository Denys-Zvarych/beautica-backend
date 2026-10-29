package com.beautica.review.invite;

import com.beautica.booking.event.GuestBookingCompletedEvent;
import com.beautica.booking.service.AppointmentTransitionService;
import com.beautica.booking.service.BookingService;
import com.beautica.common.security.PhoneHasher;
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
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 400 D1/D2 — invite creation through the real completion paths ({@code AppointmentTransitionService} →
 * {@code GuestVisitCompletedEvent} → AFTER_COMMIT {@code ReviewInviteScheduler}), both flags on.
 */
@TestPropertySource(properties = {
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
    @DisplayName("item-level completion then header completion → exactly ONE SCHEDULED invite, send_after from the "
            + "first completion, keyed by the PhoneHasher hash, no token yet")
    void should_createOneInvite_when_itemThenHeaderCompleted() {
        UUID appointmentId = fixtures.linkVisit("CONFIRMED", GUEST_PHONE, POLICY);
        UUID first = fixtures.visitItem(appointmentId, masterId, masterServiceId, "LINK", "CONFIRMED", GUEST_PHONE, 3);
        fixtures.visitItem(appointmentId, masterId, masterServiceId, "LINK", "CONFIRMED", GUEST_PHONE, 2);
        Instant before = Instant.now();

        authenticateAsMaster();
        appointmentTransitionService.completeAppointmentItem(masterUserId, appointmentId, first);
        Instant after = Instant.now();
        appointmentTransitionService.completeAppointment(masterUserId, appointmentId);

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
    @DisplayName("whole-visit completion of a LINK visit → one SCHEDULED invite")
    void should_createInvite_when_wholeVisitCompleted() {
        UUID appointmentId = confirmedLinkVisit(GUEST_PHONE, POLICY);

        authenticateAsMaster();
        appointmentTransitionService.completeAppointment(masterUserId, appointmentId);

        assertThat(statusOf(appointmentId)).isEqualTo("SCHEDULED");
    }

    @Test
    @DisplayName("a STAFF walk-in visit for an unregistered guest (phone on file) never gets an invite")
    void should_createNoInvite_when_staffVisitCompleted() {
        UUID appointmentId = fixtures.staffGuestVisit("CONFIRMED", GUEST_PHONE);
        fixtures.visitItem(appointmentId, masterId, masterServiceId, "STAFF", "CONFIRMED", GUEST_PHONE, 3);

        authenticateAsMaster();
        appointmentTransitionService.completeAppointment(masterUserId, appointmentId);

        assertThat(inviteCount()).isZero();
    }

    @Test
    @DisplayName("an opted-out phone → the row is SKIPPED (OPTED_OUT) from birth")
    void should_createSkippedInvite_when_phoneOptedOut() {
        smsOptOutRepository.saveAndFlush(
                SmsOptOut.of(phoneHasher.hash(GUEST_PHONE), SmsOptOutSource.REVIEW_INVITE, Instant.now()));
        UUID appointmentId = confirmedLinkVisit(GUEST_PHONE, POLICY);

        authenticateAsMaster();
        appointmentTransitionService.completeAppointment(masterUserId, appointmentId);

        assertThat(statusOf(appointmentId)).isEqualTo("SKIPPED");
        assertThat(skipReasonOf(appointmentId)).isEqualTo("OPTED_OUT");
    }

    @Test
    @DisplayName("a guest who accepted an older privacy policy → SKIPPED (POLICY_VERSION)")
    void should_createSkippedInvite_when_policyVersionOlder() {
        UUID appointmentId = confirmedLinkVisit(GUEST_PHONE, "2026-10-19");

        authenticateAsMaster();
        appointmentTransitionService.completeAppointment(masterUserId, appointmentId);

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

    private String bookingInviteColumn(UUID bookingId, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM review_invites WHERE booking_id = ?", String.class, bookingId);
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
