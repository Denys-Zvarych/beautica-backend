package com.beautica.user;

import com.beautica.auth.dto.SelfRegistrationRole;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 488 AC5 (user requirement 2026-11-12) — after an owner self-delete, the SAME email re-registered is a
 * brand-new account that reaches NONE of the old data: every owner/master/salon-scoped read is empty, every
 * management or detail endpoint on an old id is 403/404. Plus the other side of D2: the client who booked at
 * the detached salon still sees that booking in the list AND the detail.
 *
 * <p>Every list assertion is paired with seeded old data (notification, salon block, invite, booking), so an
 * empty list is evidence, not a vacuous pass.
 */
@DisplayName("Owner self-delete — no residual access for a re-registered email (Phase 488 AC5)")
class OwnerSelfDeleteResidualAccessIT extends AbstractOwnerSelfDeleteWorldIT {

    @Nested
    @DisplayName("same email re-registered as SALON_OWNER")
    class ReRegisteredAsOwner {

        @Test
        @DisplayName("GET /salons/mine is empty and GET /masters/me is 404")
        void should_returnEmptySalonsAndNoMasterProfile_when_ownerEmailReRegistered() throws Exception {
            OldWorld old = buildDeletedSalonWorld();
            deleteAccount(old);

            String fresh = reRegister(old.email(), SelfRegistrationRole.SALON_OWNER);

            ResponseEntity<String> mine = get("/api/v1/salons/mine", fresh);
            assertThat(mine.getStatusCode()).as("body: %s", mine.getBody()).isEqualTo(HttpStatus.OK);
            assertThat(json(mine).path("data").isArray()).isTrue();
            assertThat(json(mine).path("data").size()).as("GET /salons/mine — body: %s", mine.getBody()).isZero();
            assertThat(get("/api/v1/masters/me", fresh).getStatusCode())
                    .as("no master profile is reachable").isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("provider booking list, notification feed and client-block list are empty despite seeded old rows")
        void should_returnEmptyLists_when_ownerEmailReRegistered() throws Exception {
            OldWorld old = buildDeletedSalonWorld();
            deleteAccount(old);

            String fresh = reRegister(old.email(), SelfRegistrationRole.SALON_OWNER);

            ResponseEntity<String> bookings = get("/api/v1/bookings/me", fresh);
            assertThat(bookings.getStatusCode()).as("body: %s", bookings.getBody()).isEqualTo(HttpStatus.OK);
            assertThat(json(bookings).path("data").path("totalElements").asLong())
                    .as("GET /bookings/me — body: %s", bookings.getBody()).isZero();

            ResponseEntity<String> feed = get("/api/v1/notifications", fresh);
            assertThat(feed.getStatusCode()).as("body: %s", feed.getBody()).isEqualTo(HttpStatus.OK);
            assertThat(json(feed).path("totalElements").asLong())
                    .as("GET /notifications — body: %s", feed.getBody()).isZero();
            assertThat(json(feed).path("data").size()).isZero();

            ResponseEntity<String> blocks = get("/api/v1/blocks", fresh);
            assertThat(blocks.getStatusCode()).as("body: %s", blocks.getBody()).isEqualTo(HttpStatus.OK);
            assertThat(json(blocks).path("data").size())
                    .as("GET /blocks must not list the old salon's block — body: %s", blocks.getBody()).isZero();
            assertThat(csd.count("SELECT COUNT(*) FROM client_blocks WHERE id = ?", old.blockId()))
                    .as("precondition: the old salon's block row still exists (so the empty list is evidence)")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("every old-salon management endpoint and the old booking detail are 403/404")
        void should_denyOldSalonManagement_when_ownerEmailReRegistered() throws Exception {
            OldWorld old = buildDeletedSalonWorld();
            deleteAccount(old);
            String salon = "/api/v1/salons/" + old.salonId();

            String fresh = reRegister(old.email(), SelfRegistrationRole.SALON_OWNER);

            Map<String, ResponseEntity<String>> calls = new java.util.LinkedHashMap<>();
            calls.put("GET invites", get(salon + "/invites", fresh));
            calls.put("GET staff", get(salon + "/staff", fresh));
            calls.put("GET sibling-salons", get(salon + "/sibling-salons", fresh));
            calls.put("GET salon bookings", get("/api/v1/bookings/salon/" + old.salonId(), fresh));
            calls.put("GET salon pending-actions", get("/api/v1/bookings/salon/" + old.salonId()
                    + "/pending-actions/count", fresh));
            calls.put("GET booking detail", get("/api/v1/bookings/" + old.bookingId(), fresh));
            calls.put("PATCH salon", exchange(HttpMethod.PATCH, salon, fresh,
                    Map.of("name", "Hijacked", "street", "Hijack St", "buildingNo", "1", "cityId", testCityId())));
            calls.put("POST invite", exchange(HttpMethod.POST, salon + "/invite", fresh,
                    Map.of("email", "hijack-" + System.nanoTime() + "@beautica.test", "salonId", old.salonId())));
            calls.put("DELETE salon", exchange(HttpMethod.DELETE, salon, fresh, null));
            calls.put("DELETE invite", exchange(HttpMethod.DELETE, salon + "/invites/" + old.inviteId(), fresh, null));
            calls.put("DELETE block", exchange(HttpMethod.DELETE, "/api/v1/blocks/" + old.blockId(), fresh, null));

            calls.forEach((name, response) -> {
                if (name.equals("DELETE block")) {
                    // D5: unblock is idempotent 204 even out of scope — the evidence is the row surviving.
                    return;
                }
                assertThat(response.getStatusCode())
                        .as("%s must be denied to the re-registered account — body: %s", name, response.getBody())
                        .isIn(HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);
            });
            assertThat(csd.count("SELECT COUNT(*) FROM client_blocks WHERE id = ?", old.blockId()))
                    .as("an out-of-scope unblock must not delete the old salon's block").isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("SELECT name FROM salons WHERE id = ?", String.class,
                    old.salonId())).as("PATCH did not land").isEqualTo(SALON_NAME);
            assertThat(csd.count("SELECT COUNT(*) FROM invite_tokens WHERE salon_id = ?", old.salonId()))
                    .as("POST invite did not land").isZero();
            assertThat(jdbcTemplate.queryForObject("SELECT owner_id FROM salons WHERE id = ?", java.util.UUID.class,
                    old.salonId())).as("never relinked by email").isNull();
        }
    }

    @Nested
    @DisplayName("same email re-registered as CLIENT")
    class ReRegisteredAsClient {

        @Test
        @DisplayName("bookings, favourites and notifications are empty; the old booking detail is denied")
        void should_returnEmptyClientData_when_ownerEmailReRegisteredAsClient() throws Exception {
            OldWorld old = buildDeletedSalonWorld();
            deleteAccount(old);

            String fresh = reRegister(old.email(), SelfRegistrationRole.CLIENT);

            ResponseEntity<String> bookings = get("/api/v1/bookings/me", fresh);
            assertThat(bookings.getStatusCode()).as("body: %s", bookings.getBody()).isEqualTo(HttpStatus.OK);
            assertThat(json(bookings).path("data").path("totalElements").asLong()).isZero();
            for (String path : new String[] {"/api/v1/favorites/salons", "/api/v1/favorites/masters"}) {
                ResponseEntity<String> favs = get(path, fresh);
                assertThat(favs.getStatusCode()).as("%s — body: %s", path, favs.getBody()).isEqualTo(HttpStatus.OK);
                assertThat(favs.getBody()).as("%s leaks the old salon/master", path)
                        .doesNotContain(old.salonId().toString()).doesNotContain(old.ownerMasterId().toString());
            }
            ResponseEntity<String> feed = get("/api/v1/notifications", fresh);
            assertThat(json(feed).path("totalElements").asLong()).as("body: %s", feed.getBody()).isZero();
            assertThat(get("/api/v1/bookings/" + old.bookingId(), fresh).getStatusCode())
                    .isIn(HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("the client who booked at the detached salon")
    class ClientHistory {

        @Test
        @DisplayName("still sees the past booking in GET /bookings/me after the owner is deleted")
        void should_listPastBooking_when_ownerOfSalonDeleted() throws Exception {
            OldWorld old = buildDeletedSalonWorld();
            deleteAccount(old);

            ResponseEntity<String> list = get("/api/v1/bookings/me", fixtures.tokenFor(old.clientEmail()));

            assertThat(list.getStatusCode()).as("body: %s", list.getBody()).isEqualTo(HttpStatus.OK);
            JsonNode rows = json(list).path("data").path("data");
            assertThat(rows.size()).isEqualTo(1);
            assertThat(rows.get(0).path("id").asText()).isEqualTo(old.bookingId().toString());
            assertThat(rows.get(0).path("salonName").asText()).isEqualTo(SALON_NAME);
        }

        @Test
        @DisplayName("still opens the past booking detail (stub master, ownerless salon) — 200, not 403/404")
        void should_returnBookingDetail_when_masterIsNameStubAndSalonOwnerless() throws Exception {
            OldWorld old = buildDeletedSalonWorld();
            deleteAccount(old);
            assertThat(csd.masterUserId(old.ownerMasterId())).as("precondition: master is a name-only stub").isNull();

            ResponseEntity<String> detail = get("/api/v1/bookings/" + old.bookingId(),
                    fixtures.tokenFor(old.clientEmail()));

            assertThat(detail.getStatusCode()).as("body: %s", detail.getBody()).isEqualTo(HttpStatus.OK);
            JsonNode data = json(detail).path("data");
            assertThat(data.path("id").asText()).isEqualTo(old.bookingId().toString());
            assertThat(data.path("status").asText()).isEqualTo("COMPLETED");
            assertThat(data.path("salonName").asText()).isEqualTo(SALON_NAME);
            assertThat(data.path("clientId").asText()).isEqualTo(old.clientId().toString());
        }
    }
}
