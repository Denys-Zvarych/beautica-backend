package com.beautica.client.provider;

import com.beautica.auth.registration.AbstractPhoneIdentityIT;
import com.beautica.booking.enums.AttachReason;
import com.beautica.booking.retention.GuestPiiAnonymisationService;
import com.beautica.client.service.ClientPhoneAttachService;
import com.beautica.common.security.PhoneHasher;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B527 (CLI-62..66) — the attach when a client VERIFIES a phone (registration, add phone, phone change), V261's backfill
 * and the self-delete detach of attached rows, over real Postgres: cases 4, 5, 6, 7, 8, 9, 20, 23, 24 plus the guest-PII
 * retention job leaving attached rows alone.
 */
@DisplayName("Client phone attach at phone verification — B527")
class ClientPhoneAttachVerifyIT extends AbstractPhoneIdentityIT {

    private static final String REGISTER_URL = "/api/v1/auth/register";
    private static final String PHONE_SEND_URL = "/api/v1/users/me/phone/otp/send";
    private static final String PHONE_VERIFY_URL = "/api/v1/users/me/phone/otp/verify";

    @Autowired private PhoneHasher phoneHasher;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private ClientPhoneAttachService attachService;
    @Autowired private GuestPiiAnonymisationService anonymisationService;

    private ProviderClientFixtures fx;
    /** CONFIRMED rows must not overlap (no_overlapping_bookings): every future row gets its own day. */
    private int futureDay = 5;

    @BeforeEach
    void setUpFixtures() {
        fx = new ProviderClientFixtures(jdbcTemplate, restTemplate, objectMapper, phoneHasher, this::testCityId,
                this::resolveUnusedServiceTypeId);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** The five rows of case 4: 3 LINK + 2 STAFF over CONFIRMED / COMPLETED / CANCELLED / DECLINED. */
    private List<UUID> seedFiveRows(ProviderClientFixtures.Salon s, String phone) {
        return List.of(
                fx.guestBooking(s.masterId(), s.salonId(), "LINK", phone, "Іванна", "Коваль", "CONFIRMED", fx.at(futureDay++)),
                fx.guestBooking(s.masterId(), s.salonId(), "LINK", phone, "Іванна", "Коваль", "COMPLETED", fx.at(-8)),
                fx.guestBooking(s.masterId(), s.salonId(), "LINK", phone, "Іванна", "Коваль", "CANCELLED", fx.at(-7)),
                fx.guestBooking(s.masterId(), s.salonId(), "STAFF", phone, "Іванна", "Коваль", "DECLINED", fx.at(-6)),
                fx.guestBooking(s.masterId(), s.salonId(), "STAFF", phone, "Іванна", "Коваль", "COMPLETED", fx.at(-5)));
    }

    private Map<String, Object> row(UUID bookingId) {
        return jdbcTemplate.queryForMap("SELECT client_id, client_link_reason, client_linked_at, guest_phone, "
                + "guest_name, guest_surname, cancel_token_hash, client_detached_at FROM bookings WHERE id = ?",
                bookingId);
    }

    private ResponseEntity<String> register(String phone, String role) {
        return registerFrom(clientIp(), phone, role);
    }

    /** From another address: the register endpoint's per-IP bucket is small, a second register needs its own. */
    private ResponseEntity<String> registerFrom(String ip, String phone, String role) {
        Map<String, Object> body = new HashMap<>();
        body.put("registrationTicket", ticketFor(phone));
        body.put("password", PASSWORD);
        body.put("firstName", "Олена");
        body.put("role", role);
        return postFrom(ip, REGISTER_URL, consent(body));
    }

    private UUID userIdOfPhone(String phone) {
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE phone_number = ?", UUID.class, phone);
    }

    private void verifyPhoneChange(String token, String phone, String password) {
        assertThat(postAs(token, PHONE_SEND_URL, Map.of("phone", phone)).getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = new HashMap<>(Map.of("phone", phone, "code", lastCodeSentTo(phone)));
        if (password != null) {
            body.put("currentPassword", password);
        }
        ResponseEntity<String> verified = postAs(token, PHONE_VERIFY_URL, body);
        assertThat(verified.getStatusCode()).as("body: %s", verified.getBody()).isEqualTo(HttpStatus.OK);
    }

    private void assertAttached(List<UUID> bookings, UUID account, String reason) {
        for (UUID id : bookings) {
            Map<String, Object> r = row(id);
            assertThat(r.get("client_id")).as("client_id of %s", id).isEqualTo(account);
            assertThat(r.get("client_link_reason")).isEqualTo(reason);
            assertThat(r.get("client_linked_at")).isNotNull();
            assertThat(r.get("guest_phone")).isNull();
            assertThat(r.get("guest_name")).as("name kept").isNotNull();
        }
    }

    private int notificationsOf(UUID user) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM in_app_notification WHERE recipient_user_id = ?",
                Integer.class, user);
    }

    // ── cases ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("4: 3 LINK + 2 STAFF bookings of every status with phone P, then P registers as a client — all five "
            + "attach (PHONE_VERIFIED) and no notification is written")
    void should_attachAllFiveRows_when_clientRegistersWithThePhone() {
        ProviderClientFixtures.Salon s = fx.salon();
        String phone = newPhone();
        List<UUID> rows = seedFiveRows(s, phone);

        ResponseEntity<String> registered = register(phone, "CLIENT");

        assertThat(registered.getStatusCode()).as(registered.getBody()).isEqualTo(HttpStatus.OK);
        UUID account = userIdOfPhone(phone);
        assertAttached(rows, account, "PHONE_VERIFIED");
        assertThat(notificationsOf(account)).as("a retroactive attach tells nobody").isZero();
        assertThat(notificationsOf(s.masterUserId())).isZero();
    }

    @Test
    @DisplayName("audit LOW: a retroactive attach drops the guest cancel token — the old SMS link no longer cancels")
    void should_notCancelTheBooking_when_theOldGuestLinkIsUsedAfterTheRetroactiveAttach() {
        ProviderClientFixtures.Salon s = fx.salon();
        String phone = newPhone();
        UUID booking = fx.guestBooking(s.masterId(), s.salonId(), "LINK", phone, "Іванна", "Коваль", "CONFIRMED",
                fx.at(futureDay++));
        UUID rawToken = UUID.randomUUID();
        jdbcTemplate.update("UPDATE bookings SET cancel_token_hash = ? WHERE id = ?",
                com.beautica.booking.domain.CancelTokenHasher.hash(rawToken), booking);

        register(phone, "CLIENT");

        assertAttached(List.of(booking), userIdOfPhone(phone), "PHONE_VERIFIED");
        assertThat(row(booking).get("cancel_token_hash")).as("token hash gone on a retroactive attach").isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT cancel_token_sealed FROM bookings WHERE id = ?", String.class,
                booking)).isNull();
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", clientIp());
        ResponseEntity<String> cancelled = restTemplate.exchange("/api/v1/book/cancel/" + rawToken, HttpMethod.POST,
                new HttpEntity<>(headers), String.class);
        assertThat(cancelled.getStatusCode()).as(cancelled.getBody()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, booking))
                .isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("audit LOW: a lock timeout during the retroactive attach still completes the registration; the rows "
            + "attach on a later attach")
    void should_registerSuccessfully_and_attachLater_when_theAttachHitsALockTimeout() throws Exception {
        ProviderClientFixtures.Salon s = fx.salon();
        String phone = newPhone();
        List<UUID> rows = seedFiveRows(s, phone);
        java.util.concurrent.CountDownLatch locked = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService holder = java.util.concurrent.Executors.newSingleThreadExecutor();
        // Another transaction owns this phone's per-phone advisory lock, so the attach's lock wait times out (3s).
        java.util.concurrent.Future<?> holding = holder.submit(() -> transactionTemplate.executeWithoutResult(st -> {
            attachService.lockAndFindAccount(phone);
            locked.countDown();
            try {
                release.await(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(locked.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        try {
            ResponseEntity<String> registered = register(phone, "CLIENT");

            assertThat(registered.getStatusCode()).as(registered.getBody()).isEqualTo(HttpStatus.OK);
            UUID account = userIdOfPhone(phone);
            for (UUID id : rows) {
                assertThat(row(id).get("client_id")).as("not attached while the lock is held").isNull();
            }
            release.countDown();
            holding.get(10, java.util.concurrent.TimeUnit.SECONDS);

            transactionTemplate.executeWithoutResult(st -> attachService.attachByPhone(phone, AttachReason.PHONE_VERIFIED));

            assertAttached(rows, account, "PHONE_VERIFIED");
        } finally {
            release.countDown();
            holder.shutdownNow();
        }
    }

    @Test
    @DisplayName("5: anonymised and detached rows are never attached; the PII retention job leaves attached rows alone")
    void should_neverAttachAnonymisedOrDetached_and_retentionSkipsAttached() {
        ProviderClientFixtures.Salon s = fx.salon();
        String phone = newPhone();
        UUID anonymised = fx.anonymisedGuestBooking(s.masterId(), s.salonId(), fx.at(-30));
        UUID detached = fx.detachedBooking(s.masterId(), s.salonId(), fx.at(-30));
        UUID live = fx.guestBooking(s.masterId(), s.salonId(), "LINK", phone, "Живий", "Гість", "COMPLETED", fx.at(-20));
        register(phone, "CLIENT");

        assertThat(row(anonymised).get("client_id")).isNull();
        assertThat(row(anonymised).get("client_link_reason")).isNull();
        assertThat(row(detached).get("client_id")).isNull();
        assertThat(row(detached).get("client_link_reason")).isNull();
        assertAttached(List.of(live), userIdOfPhone(phone), "PHONE_VERIFIED");

        anonymisationService.anonymiseDue(Instant.now().plusSeconds(3600L * 24 * 400));

        assertThat(row(live).get("client_id")).as("retention never sees a row that has a client").isNotNull();
        assertThat(row(live).get("guest_name")).isEqualTo("Живий");
    }

    @Test
    @DisplayName("6a: PHONE_ADD — a client without a verified phone adds P; the bookings of P attach and the "
            + "passport cache is evicted")
    void should_attachAndEvictPassport_when_clientAddsAPhone() throws Exception {
        ProviderClientFixtures.Salon s = fx.salon();
        String phone = newPhone();
        List<UUID> rows = seedFiveRows(s, phone);
        String email = newEmail();
        UUID account = seedUser("CLIENT", email, true, null, null);
        String token = accessToken(email);
        assertThat(passportBookings(token)).as("primed before the attach").isZero();

        verifyPhoneChange(token, phone, null);

        assertAttached(rows, account, "PHONE_VERIFIED");
        assertThat(passportBookings(token)).as("two attached COMPLETED rows, cache evicted (case 24)").isEqualTo(2);
    }

    private int passportBookings(String token) throws Exception {
        ResponseEntity<String> resp = restTemplate.exchange("/api/v1/clients/me/passport", HttpMethod.GET,
                new HttpEntity<>(bearer(token)), String.class);
        assertThat(resp.getStatusCode()).as(resp.getBody()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(resp.getBody()).at("/data/bookingsConsidered").asInt();
    }

    @Test
    @DisplayName("6b/7: PHONE_CHANGE P -> Q — Q's rows attach, P's attached rows stay, a new walk-in with P stays a guest")
    void should_attachNewPhoneOnly_when_clientChangesThePhone() {
        ProviderClientFixtures.Salon s = fx.salon();
        String phoneP = newPhone();
        String phoneQ = newPhone();
        UUID account = seedUser("CLIENT", null, false, phoneP, null);
        List<UUID> pRows = seedFiveRows(s, phoneP);
        transactionTemplate.executeWithoutResult(st -> attachService.attachByPhone(phoneP, AttachReason.PHONE_VERIFIED));
        assertAttached(pRows, account, "PHONE_VERIFIED");
        List<UUID> qRows = seedFiveRows(s, phoneQ);
        String token = accessToken(phoneP);

        verifyPhoneChange(token, phoneQ, PASSWORD);

        assertAttached(pRows, account, "PHONE_VERIFIED");
        assertAttached(qRows, account, "PHONE_VERIFIED");
        Boolean oldPhoneMatches = transactionTemplate.execute(
                st -> attachService.lockAndFindAccount(phoneP).isPresent());
        assertThat(oldPhoneMatches).as("a booking under the old phone no longer resolves to the account").isFalse();
        UUID newWalkIn = fx.guestBooking(s.masterId(), s.salonId(), "STAFF", phoneP, "Новий", "Гість", "CONFIRMED", fx.at(futureDay++));
        transactionTemplate.executeWithoutResult(st -> attachService.attachByPhone(phoneP, AttachReason.PHONE_VERIFIED));
        assertThat(row(newWalkIn).get("client_id")).isNull();
        assertThat(row(newWalkIn).get("guest_phone")).isEqualTo(phoneP);
    }

    @Test
    @DisplayName("8: the phone verified on a SALON_OWNER / INDEPENDENT_MASTER account attaches nothing (CLIENT only)")
    void should_attachNothing_when_phoneBelongsToAProviderAccount() {
        ProviderClientFixtures.Salon s = fx.salon();
        String ownerPhone = newPhone();
        String masterPhone = newPhone();
        seedUser("SALON_OWNER", null, false, ownerPhone, null);
        seedUser("INDEPENDENT_MASTER", null, false, masterPhone, null);
        List<UUID> ownerRows = seedFiveRows(s, ownerPhone);
        List<UUID> masterRows = seedFiveRows(s, masterPhone);

        Integer attached = transactionTemplate.execute(st -> attachService.attachByPhone(ownerPhone,
                AttachReason.PHONE_VERIFIED) + attachService.attachByPhone(masterPhone, AttachReason.PHONE_VERIFIED));

        assertThat(attached).isZero();
        ownerRows.forEach(id -> assertThat(row(id).get("client_id")).isNull());
        masterRows.forEach(id -> assertThat(row(id).get("guest_phone")).isNotNull());
    }

    @Test
    @DisplayName("9: an unverified legacy phone_number equal to P attaches nothing")
    void should_attachNothing_when_phoneIsNotVerified() {
        ProviderClientFixtures.Salon s = fx.salon();
        String phone = newPhone();
        seedUser("CLIENT", newEmail(), true, null, phone);
        List<UUID> rows = seedFiveRows(s, phone);

        Integer attached = transactionTemplate.execute(
                st -> attachService.attachByPhone(phone, AttachReason.PHONE_VERIFIED));

        assertThat(attached).isZero();
        rows.forEach(id -> assertThat(row(id).get("client_id")).isNull());
    }

    @Test
    @DisplayName("20: self-delete after attach detaches the rows (name -> sentinel, surname + cancel token gone); "
            + "re-registering the same phone attaches nothing")
    void should_detachAndNeverReattach_when_attachedClientDeletesTheAccount() throws Exception {
        ProviderClientFixtures.Salon s = fx.salon();
        String phone = newPhone();
        List<UUID> rows = seedFiveRows(s, phone);
        JsonNode auth = json(register(phone, "CLIENT")).at("/data/auth");
        assertAttached(rows, userIdOfPhone(phone), "PHONE_VERIFIED");

        ResponseEntity<Void> deleted = restTemplate.exchange("/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(bearer(auth.at("/accessToken").asText())), Void.class);

        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        for (UUID id : rows) {
            Map<String, Object> r = row(id);
            assertThat(r.get("client_id")).as("detached").isNull();
            assertThat(r.get("client_detached_at")).isNotNull();
            assertThat(r.get("guest_name")).isEqualTo("Видалений клієнт");
            assertThat(r.get("guest_surname")).isNull();
            assertThat(r.get("cancel_token_hash")).isNull();
            assertThat(r.get("guest_phone")).isNull();
        }
        elapseResendCooldown(phone);
        ResponseEntity<String> again = registerFrom("10.201." + (System.nanoTime() % 250) + ".7", phone, "CLIENT");
        assertThat(again.getStatusCode()).as(again.getBody()).isEqualTo(HttpStatus.OK);
        UUID fresh = userIdOfPhone(phone);
        rows.forEach(id -> assertThat(row(id).get("client_id")).as("a detached row never re-attaches").isNull());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bookings WHERE client_id = ?", Integer.class, fresh))
                .isZero();
    }

    @Test
    @DisplayName("audit LOW: the retry sweep attaches rows a failed after-commit attach left, only for a verified CLIENT phone, bounded, idempotent")
    void should_sweepLeftoverRows_when_aVerifiedClientOwnsThePhone() {
        ProviderClientFixtures.Salon s = fx.salon();
        String phone = newPhone();
        String otherPhone = newPhone();
        String unverifiedPhone = newPhone();
        String providerPhone = newPhone();
        seedUser("CLIENT", null, false, phone, null);
        seedUser("CLIENT", null, false, otherPhone, null);
        seedUser("CLIENT", newEmail(), true, null, unverifiedPhone);
        seedUser("SALON_OWNER", null, false, providerPhone, null);
        List<UUID> expected = seedFiveRows(s, phone);
        List<UUID> bounded = seedFiveRows(s, otherPhone);
        List<UUID> notExpected = new java.util.ArrayList<>(seedFiveRows(s, unverifiedPhone));
        notExpected.addAll(seedFiveRows(s, providerPhone));
        notExpected.add(fx.anonymisedGuestBooking(s.masterId(), s.salonId(), fx.at(-30)));
        notExpected.add(fx.detachedBooking(s.masterId(), s.salonId(), fx.at(-30)));

        int firstRun = attachService.sweep(1);

        assertThat(firstRun).as("one phone per run when bounded to 1").isEqualTo(5);
        int second = attachService.sweep(200);
        assertThat(second).as("the other phone on the next run").isEqualTo(5);
        assertThat(attachService.sweep(200)).as("idempotent: nothing left").isZero();
        assertAttached(expected, userIdOfPhone(phone), "PHONE_VERIFIED");
        assertAttached(bounded, userIdOfPhone(otherPhone), "PHONE_VERIFIED");
        notExpected.forEach(id -> assertThat(row(id).get("client_link_reason")).isNull());
    }

    @Test
    @DisplayName("23: V261 on seeded data attaches exactly what the service predicate attaches; a second run changes 0 rows")
    void should_backfillIdempotently_when_v261RunsTwice() throws Exception {
        ProviderClientFixtures.Salon s = fx.salon();
        String phone = newPhone();
        String unverifiedPhone = newPhone();
        String providerPhone = newPhone();
        seedUser("CLIENT", null, false, phone, null);
        seedUser("CLIENT", newEmail(), true, null, unverifiedPhone);
        seedUser("SALON_OWNER", null, false, providerPhone, null);
        List<UUID> expected = seedFiveRows(s, phone);
        List<UUID> notExpected = new java.util.ArrayList<>(seedFiveRows(s, unverifiedPhone));
        notExpected.addAll(seedFiveRows(s, providerPhone));
        notExpected.add(fx.anonymisedGuestBooking(s.masterId(), s.salonId(), fx.at(-30)));
        notExpected.add(fx.detachedBooking(s.masterId(), s.salonId(), fx.at(-30)));
        String sql = new String(new ClassPathResource(
                "db/migration/V261__attach_guest_bookings_to_verified_clients.sql").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);

        transactionTemplate.executeWithoutResult(st -> jdbcTemplate.execute(sql));
        Set<UUID> attached = Set.copyOf(jdbcTemplate.queryForList(
                "SELECT id FROM bookings WHERE client_link_reason = 'BACKFILL'", UUID.class));
        Map<UUID, Object> stamps = jdbcTemplate.queryForList(
                "SELECT id, client_linked_at FROM bookings WHERE client_link_reason = 'BACKFILL'").stream()
                .collect(Collectors.toMap(m -> (UUID) m.get("id"), m -> m.get("client_linked_at")));
        transactionTemplate.executeWithoutResult(st -> jdbcTemplate.execute(sql));

        assertThat(attached).containsExactlyInAnyOrderElementsOf(expected);
        notExpected.forEach(id -> assertThat(row(id).get("client_link_reason")).isNull());
        assertAttached(expected, userIdOfPhone(phone), "BACKFILL");
        Map<UUID, Object> after = jdbcTemplate.queryForList(
                "SELECT id, client_linked_at FROM bookings WHERE client_link_reason = 'BACKFILL'").stream()
                .collect(Collectors.toMap(m -> (UUID) m.get("id"), m -> m.get("client_linked_at")));
        assertThat(after).as("second run: same rows, untouched stamps").isEqualTo(stamps);
    }

    @Test
    @DisplayName("lifecycle: a guest link booking (CONFIRMED) and a walk-in (COMPLETED) exist, the person registers with "
            + "that phone — both are in the client's own GET /bookings/me with their real status")
    void should_listBothBookingsWithTheirStatus_when_theGuestRegistersLater() throws Exception {
        ProviderClientFixtures.Salon s = fx.salon();
        String phone = newPhone();
        UUID linkBooking = fx.guestBooking(s.masterId(), s.salonId(), "LINK", phone, "Іванна", "Коваль", "CONFIRMED",
                fx.at(futureDay++));
        UUID walkIn = fx.guestBooking(s.masterId(), s.salonId(), "STAFF", phone, "Іванна", "Коваль", "COMPLETED",
                fx.at(-9));

        JsonNode auth = json(register(phone, "CLIENT")).at("/data/auth");

        ResponseEntity<String> mine = restTemplate.exchange(
                "/api/v1/bookings/me?status=CONFIRMED&status=COMPLETED", HttpMethod.GET,
                new HttpEntity<>(bearer(auth.at("/accessToken").asText())), String.class);
        assertThat(mine.getStatusCode()).as(mine.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode page = objectMapper.readTree(mine.getBody()).path("data");
        JsonNode items = page.has("data") ? page.get("data") : page;
        Map<String, String> statusById = new HashMap<>();
        items.forEach(n -> statusById.put(n.path("id").asText(), n.path("status").asText()));
        assertThat(statusById).as("own bookings, actual=%s", statusById)
                .containsEntry(linkBooking.toString(), "CONFIRMED")
                .containsEntry(walkIn.toString(), "COMPLETED")
                .hasSize(2);
    }

    @Test
    @DisplayName("V260 attached CHECK arm: a blank guest_name is rejected on an attached LINK and an attached STAFF row, "
            + "a null guest_name on an attached LINK row")
    void should_rejectBlankGuestName_when_theRowIsAttached() {
        ProviderClientFixtures.Salon s = fx.salon();
        String phone = newPhone();
        UUID link = fx.guestBooking(s.masterId(), s.salonId(), "LINK", phone, "Іванна", "Коваль", "COMPLETED", fx.at(-12));
        UUID staff = fx.guestBooking(s.masterId(), s.salonId(), "STAFF", phone, "Іванна", "Коваль", "COMPLETED", fx.at(-11));
        register(phone, "CLIENT");
        assertAttached(List.of(link, staff), userIdOfPhone(phone), "PHONE_VERIFIED");

        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE bookings SET guest_name = '   ' WHERE id = ?", link))
                .as("attached LINK, blank name").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE bookings SET guest_name = NULL WHERE id = ?", link))
                .as("attached LINK, null name").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE bookings SET guest_name = '   ' WHERE id = ?", staff))
                .as("attached STAFF, blank name").isInstanceOf(DataIntegrityViolationException.class);
        assertThat(row(link).get("guest_name")).isEqualTo("Іванна");
    }
}
