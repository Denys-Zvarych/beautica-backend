package com.beautica.user;

import com.beautica.support.StaffInviteFixtures;
import com.beautica.auth.InvitePersistenceService;
import com.beautica.auth.Role;
import com.beautica.auth.dto.InviteAcceptRequest;
import com.beautica.auth.dto.SelfRegistrationRole;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * Phase 488 — user scenario 2026-11-13: an owner builds a staffed salon, deletes it (SAL-21: staff hard-deleted,
 * a master with booking history kept as a name-only stub), deletes the account (SUP-67), re-registers the same
 * email, opens a new salon and re-invites the same people (B520: by phone) with the same roles. The re-invites must not hit
 * AUTH-18/19's 409, and the new staff accounts must reach nothing of the first salon.
 */
@DisplayName("Owner re-invites the same staff after salon + account delete (Phase 488 scenario)")
class OwnerReinviteStaffAfterAccountDeleteIT extends AbstractOwnerSelfDeleteWorldIT {

    private static final String STAFF_PASSWORD = "N0vaP@rol9x!";
    private static final String OLD_SALON = "Перший салон";
    private static final String MASTER_FIRST = "Марина";

    @Test
    @DisplayName("salon + account deleted → same email re-registers, re-invites admin/master/pending invitee "
            + "(201 each, no 409), new staff see only the new salon; the client keeps the stub master by name")
    void should_reinviteSameStaffIntoNewSalon_when_ownerDeletedSalonAndAccount() throws Exception {
        String ownerEmail = "owner-reinv-" + System.nanoTime() + "@beautica.test";
        String adminPhone = StaffInviteFixtures.newPhone();
        String masterPhone = StaffInviteFixtures.newPhone();
        String pendingPhone = StaffInviteFixtures.newPhone();

        // ── 1. owner + S1 + accepted admin/master + one pending invite + a client booking with the master ──
        String ownerAccess = reRegister(ownerEmail, SelfRegistrationRole.SALON_OWNER);
        UUID oldOwnerId = userIdOf(ownerEmail);
        UUID s1 = createSalon(ownerAccess, OLD_SALON);
        invite(ownerAccess, s1, adminPhone, Role.SALON_ADMIN);
        invite(ownerAccess, s1, masterPhone, Role.SALON_MASTER);
        invite(ownerAccess, s1, pendingPhone, Role.SALON_MASTER);
        accept(adminPhone, "Алла");
        accept(masterPhone, MASTER_FIRST);
        UUID oldAdminId = userIdOfPhone(adminPhone);
        UUID oldMasterUserId = userIdOfPhone(masterPhone);
        UUID oldMasterId = masterIdOf(oldMasterUserId);
        UUID masterServiceId = insertMasterService(oldMasterId, s1);
        UUID clientId = csd.createClient();
        String clientEmail = jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class,
                clientId);
        UUID bookingId = csd.insertBooking(clientId, oldMasterId, masterServiceId, s1, "COMPLETED", PAST);

        // ── 2. delete S1, then the owner account ──
        deleteSalon(ownerAccess, s1);
        assertThat(csd.userExists(oldAdminId)).as("SAL-21: admin account hard-deleted").isFalse();
        assertThat(csd.userExists(oldMasterUserId)).as("SAL-21: master account hard-deleted").isFalse();
        assertThat(csd.masterUserId(oldMasterId)).as("SAL-21: master with history kept as a stub").isNull();
        assertThat(csd.masterDetachedFirstName(oldMasterId)).isEqualTo(MASTER_FIRST);
        assertThat(deleteMe(ownerAccess).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(csd.userExists(oldOwnerId)).isFalse();

        // ── 3. same email re-registers, opens S2, re-invites the same people with the same roles ──
        String freshOwner = reRegister(ownerEmail, SelfRegistrationRole.SALON_OWNER);
        assertThat(userIdOf(ownerEmail)).as("brand-new owner account").isNotEqualTo(oldOwnerId);
        UUID s2 = createSalon(freshOwner, "Другий салон");
        invite(freshOwner, s2, adminPhone, Role.SALON_ADMIN);
        invite(freshOwner, s2, masterPhone, Role.SALON_MASTER);
        invite(freshOwner, s2, pendingPhone, Role.SALON_MASTER);
        String newAdmin = accept(adminPhone, "Алла");
        String newMaster = accept(masterPhone, MASTER_FIRST);
        String newPending = accept(pendingPhone, "Поліна");

        assertThat(userIdOfPhone(adminPhone)).as("new admin account").isNotEqualTo(oldAdminId);
        assertThat(userIdOfPhone(masterPhone)).as("new master account").isNotEqualTo(oldMasterUserId);
        UUID newMasterId = masterIdOf(userIdOfPhone(masterPhone));
        assertThat(newMasterId).as("a new masters row, never the S1 stub relinked").isNotEqualTo(oldMasterId);
        assertThat(csd.masterUserId(oldMasterId)).as("S1 stub stays detached").isNull();
        assertMe(newAdmin, "SALON_ADMIN", s2);
        assertMe(newMaster, "SALON_MASTER", s2);
        assertMe(newPending, "SALON_MASTER", s2);

        for (String token : List.of(newAdmin, newMaster, newPending)) {
            assertDenied(get("/api/v1/bookings/" + bookingId, token), "old S1 booking detail");
            assertDenied(get("/api/v1/salons/" + s1 + "/staff", token), "old S1 staff list");
            assertNoS1Notification(token, s1, bookingId);
        }
        assertDenied(get("/api/v1/bookings/salon/" + s1, newAdmin), "S1 salon bookings (admin)");
        ResponseEntity<String> s2Bookings = get("/api/v1/bookings/salon/" + s2, newAdmin);
        assertThat(s2Bookings.getStatusCode()).as("body: %s", s2Bookings.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(json(s2Bookings).path("data").path("totalElements").asLong()).as("S2 board is empty").isZero();
        ResponseEntity<String> blocks = get("/api/v1/blocks", newAdmin);
        assertThat(blocks.getStatusCode()).as("body: %s", blocks.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(json(blocks).path("data").size()).as("no S1 clients/blocks").isZero();

        LocalDate from = PAST.toLocalDate().minusDays(1);
        ResponseEntity<String> calendar = get("/api/v1/masters/me/calendar?from=" + from + "&to=" + from.plusDays(7),
                newMaster);
        assertThat(calendar.getStatusCode()).as("body: %s", calendar.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(json(calendar).path("data").path("totalElements").asLong())
                .as("new master's calendar over the old booking's day is empty — body: %s", calendar.getBody())
                .isZero();

        ResponseEntity<String> clientList = get("/api/v1/bookings/me", fixtures.tokenFor(clientEmail));
        assertThat(clientList.getStatusCode()).as("body: %s", clientList.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode rows = json(clientList).path("data").path("data");
        assertThat(rows.size()).isEqualTo(1);
        assertThat(rows.get(0).path("id").asText()).isEqualTo(bookingId.toString());
        assertThat(rows.get(0).path("masterId").asText()).as("still the S1 stub").isEqualTo(oldMasterId.toString());
        assertThat(rows.get(0).path("masterFirstName").asText()).as("stub shown by name — body: %s",
                clientList.getBody()).isEqualTo(MASTER_FIRST);
        assertThat(rows.get(0).path("salonName").asText()).isEqualTo(OLD_SALON);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private void invite(String token, UUID salonId, String phone, Role role) {
        ResponseEntity<String> response = exchange(HttpMethod.POST, "/api/v1/salons/" + salonId + "/invite", token,
                Map.of("phone", phone, "salonId", salonId, "role", role.name()));
        assertThat(response.getStatusCode()).as("invite as %s — must not be 409 — body: %s", role,
                response.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    /** Accepts the most recent invite sent to {@code phone} (B520: by SMS); returns the new account's access token. */
    private String accept(String phone, String firstName) throws Exception {
        ArgumentCaptor<InvitePersistenceService.PhoneInvite> sent =
                ArgumentCaptor.forClass(InvitePersistenceService.PhoneInvite.class);
        verify(invitePersistenceService, atLeastOnce()).persistInviteAndEnqueue(sent.capture());
        String link = sent.getAllValues().stream().filter(invite -> invite.phoneE164().equals(phone))
                .reduce((first, second) -> second).orElseThrow().inviteLink();
        String rawToken = URLDecoder.decode(link.substring(link.indexOf("token=") + "token=".length()),
                StandardCharsets.UTF_8);
        ResponseEntity<String> response = restTemplate.postForEntity("/api/v1/auth/invite/accept",
                new InviteAcceptRequest(rawToken, STAFF_PASSWORD, firstName, "Тестова", null, null, null, null,
                        StaffInviteFixtures.seedRegistrationTicket(jdbcTemplate, phone)), String.class);
        assertThat(response.getStatusCode()).as("accept — body: %s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return json(response).path("data").path("accessToken").asText();
    }

    private void assertMe(String token, String role, UUID salonId) throws Exception {
        ResponseEntity<String> me = get("/api/v1/users/me", token);
        assertThat(me.getStatusCode()).as("body: %s", me.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode data = json(me).path("data");
        assertThat(data.path("role").asText()).isEqualTo(role);
        assertThat(data.path("salonId").asText()).as("bound to S2 only").isEqualTo(salonId.toString());
    }

    /** S2's own INVITE_ACCEPTED rows are legitimate; nothing may point at S1 or its booking. */
    private void assertNoS1Notification(String token, UUID s1, UUID oldBookingId) throws Exception {
        ResponseEntity<String> feed = get("/api/v1/notifications", token);
        assertThat(feed.getStatusCode()).as("body: %s", feed.getBody()).isEqualTo(HttpStatus.OK);
        for (JsonNode row : json(feed).path("data")) {
            assertThat(row.path("target").path("salonId").asText()).as("S1 notification leaked — body: %s",
                    feed.getBody()).isNotEqualTo(s1.toString());
            assertThat(row.path("target").path("bookingId").asText()).isNotEqualTo(oldBookingId.toString());
        }
    }

    private static void assertDenied(ResponseEntity<String> response, String what) {
        assertThat(response.getStatusCode()).as("%s must be unreachable — body: %s", what, response.getBody())
                .isIn(HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);
    }

    private UUID userIdOf(String email) {
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
    }

    private UUID userIdOfPhone(String phone) {
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE phone_number = ?", UUID.class, phone);
    }

    private UUID masterIdOf(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT id FROM masters WHERE user_id = ?", UUID.class, userId);
    }
}
