package com.beautica.moderation;

import com.beautica.common.exception.BookingUnavailableException;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.sms.OtpSmsSender;
import com.beautica.support.BookableMasterSeeder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * Phase 398 D2 — the LINK guest create paths (master link single + visit, salon link) against real Postgres: a GUEST
 * block of the OTP-verified phone refuses with {@code 409 BOOKING_UNAVAILABLE}; OTP send/verify are untouched (the
 * blocked phone still gets its code and token); a CLIENT block never refuses a guest booking even when the
 * account's profile phone equals the guest phone; another phone books normally.
 *
 * <p>Same {@code @Import} + {@code @MockBean} set as {@code PhoneOtpIntegrationTest}, so the cached context is
 * shared; the OTP code is read off the mocked {@link OtpSmsSender}.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Booking block guard — LINK guest create paths (phase 398 IT)")
class GuestBookingBlockGuardIT extends AbstractBookingBlockIT {

    private static final String BLOCKED_PHONE = "+380671234501";
    private static final String OTHER_PHONE = "+380671234502";

    @MockBean
    private OtpSmsSender otpSmsSender;

    @Test
    @DisplayName("master link: OTP send/verify still 200 for the blocked phone, then single-service and visit "
            + "creates are 409 BOOKING_UNAVAILABLE (master wording); nothing created")
    void should_reject_when_masterLinkGuestPhoneBlocked() throws Exception {
        IndependentMaster master = createIndependentMaster(unique("indep"));
        UUID first = makeIndependentBookable(master.masterId());
        UUID second = addIndependentService(master.masterId());
        String slug = setMasterSlug(master.masterId());
        String masterEmail = jdbcTemplate.queryForObject(
                "SELECT email FROM users WHERE id = ?", String.class, master.userId());
        block(tokenFor(masterEmail), insertIndependentGuestBooking(master.masterId(), first, BLOCKED_PHONE));

        String guestToken = otpGuestToken(BLOCKED_PHONE);
        ResponseEntity<String> single = guestBookSingle(guestToken, slug, first, slot(3, 12));
        ResponseEntity<String> visit = guestBookVisit(guestToken, slug, List.of(first, second), slot(4, 12));

        assertUnavailable(single, BookingUnavailableException.MASTER_MESSAGE);
        assertUnavailable(visit, BookingUnavailableException.MASTER_MESSAGE);
        assertThat(countBookingsOf(master.masterId(), "CONFIRMED")).isZero();
    }

    @Test
    @DisplayName("salon link: a salon GUEST block refuses the phone with any salon master (salon wording)")
    void should_reject_when_salonLinkGuestPhoneBlocked() throws Exception {
        SalonFixture salon = createSalon();
        UUID service = makeSalonMasterBookable(salon.salonId(), salon.masterId());
        String salonSlug = setSalonSlug(salon.salonId());
        block(tokenFor(salon.ownerEmail()), insertGuestBooking(salon, "LINK", BLOCKED_PHONE));

        ResponseEntity<String> resp = guestBookSalon(otpGuestToken(BLOCKED_PHONE), salonSlug, salon.masterId(),
                List.of(service), slot(3, 12));

        assertUnavailable(resp, BookingUnavailableException.SALON_MESSAGE);
        assertThat(countBookingsOf(salon.masterId(), "CONFIRMED")).isZero();
    }

    @Test
    @DisplayName("no cross-type: a CLIENT block of an account whose profile phone equals the guest phone does not "
            + "refuse the guest booking")
    void should_notReject_when_clientBlockMatchesGuestPhone() throws Exception {
        SalonFixture salon = createSalon();
        UUID service = makeSalonMasterBookable(salon.salonId(), salon.masterId());
        String salonSlug = setSalonSlug(salon.salonId());
        UUID client = createUser(unique("client"), "CLIENT", null);
        setProfilePhone(client, BLOCKED_PHONE);
        block(tokenFor(salon.ownerEmail()), insertCompletedBooking(client, salon));

        ResponseEntity<String> resp = guestBookSalon(otpGuestToken(BLOCKED_PHONE), salonSlug, salon.masterId(),
                List.of(service), slot(3, 12));

        assertCreated(resp);
    }

    @Test
    @DisplayName("another phone books normally on the same salon link")
    void should_allowBooking_when_otherPhone() throws Exception {
        SalonFixture salon = createSalon();
        UUID service = makeSalonMasterBookable(salon.salonId(), salon.masterId());
        String salonSlug = setSalonSlug(salon.salonId());
        block(tokenFor(salon.ownerEmail()), insertGuestBooking(salon, "LINK", BLOCKED_PHONE));

        ResponseEntity<String> resp = guestBookSalon(otpGuestToken(OTHER_PHONE), salonSlug, salon.masterId(),
                List.of(service), slot(3, 12));

        assertCreated(resp);
    }

    @Test
    @DisplayName("salon link, multi-service visit with salon master B: a salon GUEST block (made on master A's "
            + "booking) refuses it with 409 BOOKING_UNAVAILABLE; no visit or item created")
    void should_reject_when_salonLinkVisitGuestPhoneBlocked() throws Exception {
        SalonFixture salon = createSalon();
        String salonSlug = setSalonSlug(salon.salonId());
        UUID masterB = BookableMasterSeeder.addBookableSalonMaster(jdbcTemplate, salon.salonId());
        UUID firstB = jdbcTemplate.queryForObject(
                "SELECT id FROM master_services WHERE master_id = ?", UUID.class, masterB);
        UUID secondB = assignmentOf(BookableMasterSeeder.assignNewSalonService(jdbcTemplate, salon.salonId(), masterB));
        block(tokenFor(salon.ownerEmail()), insertGuestBooking(salon, "LINK", BLOCKED_PHONE));

        ResponseEntity<String> resp = guestBookSalon(otpGuestToken(BLOCKED_PHONE), salonSlug, masterB,
                List.of(firstB, secondB), slot(3, 10));

        assertUnavailable(resp, BookingUnavailableException.SALON_MESSAGE);
        assertThat(countBookingsOf(masterB, "CONFIRMED")).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM appointments WHERE salon_id = ?", Integer.class, salon.salonId())).isZero();
    }

    @Test
    @DisplayName("master link, multi-service visit: an independent master's GUEST block refuses it with 409 "
            + "BOOKING_UNAVAILABLE; no visit or item created")
    void should_reject_when_masterLinkVisitGuestPhoneBlocked() throws Exception {
        IndependentMaster master = createIndependentMaster(unique("indep"));
        UUID first = makeIndependentBookable(master.masterId());
        UUID second = addIndependentService(master.masterId());
        String slug = setMasterSlug(master.masterId());
        block(tokenFor(emailOf(master.userId())),
                insertIndependentGuestBooking(master.masterId(), first, BLOCKED_PHONE));

        ResponseEntity<String> resp = guestBookVisit(guestTokenProvider.generate(BLOCKED_PHONE), slug,
                List.of(first, second), slot(3, 10));

        assertUnavailable(resp, BookingUnavailableException.MASTER_MESSAGE);
        assertThat(countBookingsOf(master.masterId(), "CONFIRMED")).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM appointments WHERE booking_source = 'LINK' AND guest_phone = ?",
                Integer.class, BLOCKED_PHONE)).isZero();
    }

    @Test
    @DisplayName("guest unblock → the same phone books through the salon link again")
    void should_allowGuestBooking_when_guestPhoneUnblocked() throws Exception {
        SalonFixture salon = createSalon();
        UUID service = makeSalonMasterBookable(salon.salonId(), salon.masterId());
        String salonSlug = setSalonSlug(salon.salonId());
        String ownerToken = tokenFor(salon.ownerEmail());
        UUID blockId = block(ownerToken, insertGuestBooking(salon, "LINK", BLOCKED_PHONE));
        String guestToken = guestTokenProvider.generate(BLOCKED_PHONE);
        assertUnavailable(guestBookSalon(guestToken, salonSlug, salon.masterId(), List.of(service), slot(3, 12)),
                BookingUnavailableException.SALON_MESSAGE);

        ResponseEntity<String> unblock = exchange(HttpMethod.DELETE, BLOCKS_URL + "/" + blockId, ownerToken);
        ResponseEntity<String> resp = guestBookSalon(guestToken, salonSlug, salon.masterId(), List.of(service),
                slot(3, 12));

        assertThat(unblock.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertCreated(resp);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    /** Real {@code /book/otp/send} + {@code /book/otp/verify}: both must stay 200 for a blocked phone. */
    private String otpGuestToken(String phone) throws Exception {
        String ip = "10." + ThreadLocalRandom.current().nextInt(1, 255) + ".0."
                + ThreadLocalRandom.current().nextInt(1, 255);
        ResponseEntity<String> send = restTemplate.postForEntity("/api/v1/book/otp/send",
                otpJson("{\"phone\":\"" + phone + "\",\"privacyAccepted\":true,\"privacyPolicyVersion\":\""
                        + PRIVACY_VERSION + "\"}", ip), String.class);
        assertThat(send.getStatusCode()).as(send.getBody()).isEqualTo(HttpStatus.OK);
        ArgumentCaptor<String> sms = ArgumentCaptor.forClass(String.class);
        verify(otpSmsSender).send(eq(phone), sms.capture());
        String code = sms.getValue().replaceAll(".*?(\\d{6}).*", "$1");

        ResponseEntity<String> verified = restTemplate.postForEntity("/api/v1/book/otp/verify",
                otpJson("{\"phone\":\"" + phone + "\",\"code\":\"" + code + "\"}", ip), String.class);
        assertThat(verified.getStatusCode()).as(verified.getBody()).isEqualTo(HttpStatus.OK);
        return readTree(verified).path("data").path("guestToken").asText();
    }

    private String emailOf(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }

    private static HttpEntity<String> otpJson(String body, String ip) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", ip);
        return new HttpEntity<>(body, headers);
    }

    /** A past COMPLETED LINK booking of {@code phone} with an independent master — the block's source. */
    private UUID insertIndependentGuestBooking(UUID masterId, UUID masterServiceId, String phone) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, guest_name, guest_surname, guest_phone, created_at, updated_at) "
                        + "VALUES (?, NULL, ?, ?, NULL, 'COMPLETED', NOW() - interval '4 hours', "
                        + "NOW() - interval '3 hours', 500.00, 60, 0, 'LINK', 'Марія', 'Шевченко', ?, NOW(), NOW())",
                bookingId, masterId, masterServiceId, phone);
        return bookingId;
    }
}
