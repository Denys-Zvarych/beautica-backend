package com.beautica.moderation;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 397 QA — the {@code /api/v1/blocks} contract over real HTTP with real logins: the D1 role matrix (independent
 * master own scope, owner + admin share the salon list, salon master / client 403), cross-scope 404s, POST
 * idempotency (201 then 200, same id) for every blocker role and both target types, DELETE of a foreign block as a
 * silent 204 no-op, and the viewer-scoped {@code clientBlocked}/{@code clientBlockId}/{@code clientBlockable} on
 * {@code GET /bookings/{id}} for every viewer role (incl. STAFF walk-in and an erased guest).
 */
@DisplayName("Client blocks — phase 397 HTTP contract IT")
class ClientBlockHttpContractIT extends AbstractModerationIT {

    private static final String BOOKINGS_URL = "/api/v1/bookings/";
    private static final String GUEST_PHONE = "+380671234590";

    // ── role matrix ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("role matrix")
    class RoleMatrix {

        @Test
        @DisplayName("independent master blocks the client of an own booking and sees it only in the own list")
        void should_blockInOwnScope_when_independentMasterBlocksOwnBooking() throws Exception {
            String indepEmail = unique("indep");
            IndependentMaster indep = createIndependentMaster(indepEmail);
            UUID client = createUser(unique("client"), "CLIENT", null);
            UUID bookingId = insertIndependentBooking(client, indep.masterId());
            String token = tokenFor(indepEmail);

            ResponseEntity<String> created = postBlock(token, bookingId);
            JsonNode list = data(exchange(HttpMethod.GET, BLOCKS_URL, token));

            assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(data(created).path("kind").asText()).isEqualTo("CLIENT");
            assertThat(list).hasSize(1);
            assertThat(list.get(0).path("id").asText()).isEqualTo(data(created).path("id").asText());
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT blocker_master_id FROM client_blocks", UUID.class))
                    .as("an independent master's block lives in the master scope, not a salon")
                    .isEqualTo(indep.masterId());
        }

        @Test
        @DisplayName("independent master cannot block on another independent master's booking — 404, nothing stored")
        void should_return404_when_independentMasterBlocksForeignMastersBooking() throws Exception {
            String mineEmail = unique("indep-mine");
            createIndependentMaster(mineEmail);
            IndependentMaster other = createIndependentMaster(unique("indep-other"));
            UUID bookingId = insertIndependentBooking(createUser(unique("client"), "CLIENT", null), other.masterId());

            ResponseEntity<String> resp = postBlock(tokenFor(mineEmail), bookingId);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(countBlocks()).isZero();
        }

        @Test
        @DisplayName("independent master cannot block on a salon booking — 404")
        void should_return404_when_independentMasterBlocksSalonBooking() throws Exception {
            String indepEmail = unique("indep");
            createIndependentMaster(indepEmail);
            SalonFixture salon = createSalon();
            UUID bookingId = insertBooking(createUser(unique("client"), "CLIENT", null), salon, "COMPLETED");

            ResponseEntity<String> resp = postBlock(tokenFor(indepEmail), bookingId);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(countBlocks()).isZero();
        }

        @Test
        @DisplayName("owner blocks, the salon admin lists the same row and a repeat block by the admin is 200 same id")
        void should_shareSalonList_when_ownerBlocksAndAdminReads() throws Exception {
            SalonFixture salon = createSalon();
            String adminEmail = unique("admin");
            createUser(adminEmail, "SALON_ADMIN", salon.salonId());
            UUID bookingId = insertBooking(createUser(unique("client"), "CLIENT", null), salon, "COMPLETED");
            String ownerToken = tokenFor(salon.ownerEmail());
            String adminToken = tokenFor(adminEmail);

            ResponseEntity<String> byOwner = postBlock(ownerToken, bookingId);
            JsonNode adminList = data(exchange(HttpMethod.GET, BLOCKS_URL, adminToken));
            ResponseEntity<String> byAdmin = postBlock(adminToken, bookingId);

            String blockId = data(byOwner).path("id").asText();
            assertThat(byOwner.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(adminList).hasSize(1);
            assertThat(adminList.get(0).path("id").asText()).isEqualTo(blockId);
            assertThat(byAdmin.getStatusCode()).as("same salon scope → repeat").isEqualTo(HttpStatus.OK);
            assertThat(data(byAdmin).path("id").asText()).isEqualTo(blockId);
            assertThat(countBlocks()).isEqualTo(1);
        }

        @Test
        @DisplayName("owner performing their own salon booking as its master blocks into the SALON scope, not a master scope")
        void should_blockInSalonScope_when_ownerIsTheBookingsMaster() throws Exception {
            SalonFixture salon = createSalon();
            String adminEmail = unique("admin");
            createUser(adminEmail, "SALON_ADMIN", salon.salonId());
            String indepEmail = unique("indep");
            createIndependentMaster(indepEmail);
            UUID ownerId = jdbcTemplate.queryForObject(
                    "SELECT owner_id FROM salons WHERE id = ?", UUID.class, salon.salonId());
            UUID ownerMasterId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) "
                            + "VALUES (?, ?, ?, 'SALON_OWNER', true, NOW(), NOW())",
                    ownerMasterId, ownerId, salon.salonId());
            SalonFixture ownerAsMaster = new SalonFixture(
                    salon.salonId(), salon.ownerEmail(), ownerMasterId, salon.ownerEmail());
            UUID bookingId = insertBooking(createUser(unique("client"), "CLIENT", null), ownerAsMaster, "COMPLETED");
            String ownerToken = tokenFor(salon.ownerEmail());

            ResponseEntity<String> created = postBlock(ownerToken, bookingId);
            JsonNode ownerList = data(exchange(HttpMethod.GET, BLOCKS_URL, ownerToken));
            JsonNode adminList = data(exchange(HttpMethod.GET, BLOCKS_URL, tokenFor(adminEmail)));
            JsonNode indepList = data(exchange(HttpMethod.GET, BLOCKS_URL, tokenFor(indepEmail)));

            String blockId = data(created).path("id").asText();
            assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT blocker_salon_id FROM client_blocks WHERE id = ?::uuid", UUID.class, blockId))
                    .as("the owner's own-performed booking blocks for the whole salon")
                    .isEqualTo(salon.salonId());
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM client_blocks WHERE blocker_master_id IS NOT NULL", Integer.class))
                    .as("no master-scope row — not even for the owner's own SALON_OWNER master row")
                    .isZero();
            assertThat(ownerList).extracting(n -> n.path("id").asText()).containsExactly(blockId);
            assertThat(adminList).as("the salon admin shares the owner's salon list")
                    .extracting(n -> n.path("id").asText()).containsExactly(blockId);
            assertThat(indepList).as("an independent master's own scope never sees a salon block").isEmpty();
        }

        @Test
        @DisplayName("salon master is 403 on POST, GET and DELETE /blocks")
        void should_return403OnEveryVerb_when_salonMasterCalls() throws Exception {
            SalonFixture salon = createSalon();
            UUID bookingId = insertBooking(createUser(unique("client"), "CLIENT", null), salon, "COMPLETED");
            String token = tokenFor(salon.masterEmail());

            ResponseEntity<String> post = postBlock(token, bookingId);
            ResponseEntity<String> list = exchange(HttpMethod.GET, BLOCKS_URL, token);
            ResponseEntity<String> delete = exchange(HttpMethod.DELETE, BLOCKS_URL + "/" + UUID.randomUUID(), token);

            assertThat(post.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(list.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(delete.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(countBlocks()).isZero();
        }

        @Test
        @DisplayName("client is 403 on POST, GET and DELETE /blocks — even on their own booking")
        void should_return403OnEveryVerb_when_clientCalls() throws Exception {
            SalonFixture salon = createSalon();
            String clientEmail = unique("client");
            UUID client = createUser(clientEmail, "CLIENT", null);
            UUID bookingId = insertBooking(client, salon, "COMPLETED");
            String token = tokenFor(clientEmail);

            ResponseEntity<String> post = postBlock(token, bookingId);
            ResponseEntity<String> list = exchange(HttpMethod.GET, BLOCKS_URL, token);
            ResponseEntity<String> delete = exchange(HttpMethod.DELETE, BLOCKS_URL + "/" + UUID.randomUUID(), token);

            assertThat(post.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(list.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(delete.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(countBlocks()).isZero();
        }

        @Test
        @DisplayName("anonymous POST /blocks is 401")
        void should_return401_when_noToken() {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            String body = objectMapper.createObjectNode().put("bookingId", UUID.randomUUID().toString()).toString();

            ResponseEntity<String> resp = restTemplate.exchange(BLOCKS_URL, HttpMethod.POST,
                    new HttpEntity<>(body, headers), String.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    // ── cross-scope ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("cross-scope")
    class CrossScope {

        @Test
        @DisplayName("salon admin of salon A gets 404 on a salon-B booking and nothing is stored")
        void should_return404_when_adminBlocksBookingOfAnotherSalon() throws Exception {
            SalonFixture mine = createSalon();
            SalonFixture other = createSalon();
            String adminEmail = unique("admin");
            createUser(adminEmail, "SALON_ADMIN", mine.salonId());
            UUID bookingId = insertBooking(createUser(unique("client"), "CLIENT", null), other, "COMPLETED");

            ResponseEntity<String> resp = postBlock(tokenFor(adminEmail), bookingId);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(countBlocks()).isZero();
        }

        @Test
        @DisplayName("an unknown booking id is the same 404 as a foreign one")
        void should_return404_when_bookingDoesNotExist() throws Exception {
            SalonFixture salon = createSalon();

            ResponseEntity<String> resp = postBlock(tokenFor(salon.ownerEmail()), UUID.randomUUID());

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("salon B's list never shows salon A's block")
        void should_notListForeignSalonBlock_when_otherOwnerLists() throws Exception {
            SalonFixture a = createSalon();
            SalonFixture b = createSalon();
            postBlock(tokenFor(a.ownerEmail()),
                    insertBooking(createUser(unique("client"), "CLIENT", null), a, "COMPLETED"));

            JsonNode listB = data(exchange(HttpMethod.GET, BLOCKS_URL, tokenFor(b.ownerEmail())));

            assertThat(countBlocks()).isEqualTo(1);
            assertThat(listB).isEmpty();
        }
    }

    // ── POST idempotency ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST idempotency")
    class Idempotency {

        @Test
        @DisplayName("independent master: second POST is 200 with the same id, one row")
        void should_return201Then200_when_independentMasterRepeats() throws Exception {
            String indepEmail = unique("indep");
            IndependentMaster indep = createIndependentMaster(indepEmail);
            UUID bookingId = insertIndependentBooking(createUser(unique("client"), "CLIENT", null), indep.masterId());
            String token = tokenFor(indepEmail);

            ResponseEntity<String> first = postBlock(token, bookingId);
            ResponseEntity<String> second = postBlock(token, bookingId);

            assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(data(second).path("id").asText()).isEqualTo(data(first).path("id").asText());
            assertThat(countBlocks()).isEqualTo(1);
        }

        @Test
        @DisplayName("guest: a second LINK booking of the same verified phone is 200 with the existing GUEST row")
        void should_return200SameRow_when_sameGuestPhoneOnAnotherBooking() throws Exception {
            SalonFixture salon = createSalon();
            String token = tokenFor(salon.ownerEmail());
            UUID firstBooking = insertGuestBooking(salon, "LINK", GUEST_PHONE);
            UUID secondBooking = insertGuestBooking(salon, "LINK", GUEST_PHONE);

            ResponseEntity<String> first = postBlock(token, firstBooking);
            ResponseEntity<String> second = postBlock(token, secondBooking);

            assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(second.getStatusCode())
                    .as("the same verified phone on another booking matches the existing GUEST row")
                    .isEqualTo(HttpStatus.OK);
            assertThat(data(second).path("id").asText()).isEqualTo(data(first).path("id").asText());
            assertThat(data(second).path("kind").asText()).isEqualTo("GUEST");
            assertThat(countBlocks()).isEqualTo(1);
        }

        @Test
        @DisplayName("the same client blocked by an independent master and by a salon = two rows, two scopes")
        void should_createSeparateRows_when_sameClientBlockedInTwoScopes() throws Exception {
            SalonFixture salon = createSalon();
            String indepEmail = unique("indep");
            IndependentMaster indep = createIndependentMaster(indepEmail);
            UUID client = createUser(unique("client"), "CLIENT", null);

            ResponseEntity<String> bySalon = postBlock(tokenFor(salon.ownerEmail()),
                    insertBooking(client, salon, "COMPLETED"));
            ResponseEntity<String> byIndep = postBlock(tokenFor(indepEmail),
                    insertIndependentBooking(client, indep.masterId()));

            assertThat(bySalon.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(byIndep.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(countBlocks()).isEqualTo(2);
        }
    }

    // ── DELETE scope ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("DELETE outside scope")
    class ForeignDelete {

        @Test
        @DisplayName("owner of salon B deleting salon A's block gets 204 and the row survives")
        void should_return204AndKeepRow_when_otherSalonOwnerDeletes() throws Exception {
            SalonFixture a = createSalon();
            SalonFixture b = createSalon();
            String blockId = data(postBlock(tokenFor(a.ownerEmail()),
                    insertBooking(createUser(unique("client"), "CLIENT", null), a, "COMPLETED"))).path("id").asText();

            ResponseEntity<String> resp = exchange(HttpMethod.DELETE, BLOCKS_URL + "/" + blockId,
                    tokenFor(b.ownerEmail()));

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(blockExists(UUID.fromString(blockId))).as("foreign DELETE must be a no-op").isTrue();
        }

        @Test
        @DisplayName("independent master deleting a salon's block gets 204 and the row survives")
        void should_return204AndKeepRow_when_independentMasterDeletesSalonBlock() throws Exception {
            SalonFixture salon = createSalon();
            String indepEmail = unique("indep");
            createIndependentMaster(indepEmail);
            String blockId = data(postBlock(tokenFor(salon.ownerEmail()),
                    insertBooking(createUser(unique("client"), "CLIENT", null), salon, "COMPLETED")))
                    .path("id").asText();

            ResponseEntity<String> resp = exchange(HttpMethod.DELETE, BLOCKS_URL + "/" + blockId,
                    tokenFor(indepEmail));

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(blockExists(UUID.fromString(blockId))).isTrue();
        }

        @Test
        @DisplayName("salon owner deleting an independent master's block gets 204 and the row survives")
        void should_return204AndKeepRow_when_ownerDeletesIndependentMastersBlock() throws Exception {
            SalonFixture salon = createSalon();
            String indepEmail = unique("indep");
            IndependentMaster indep = createIndependentMaster(indepEmail);
            String blockId = data(postBlock(tokenFor(indepEmail), insertIndependentBooking(
                    createUser(unique("client"), "CLIENT", null), indep.masterId()))).path("id").asText();

            ResponseEntity<String> resp = exchange(HttpMethod.DELETE, BLOCKS_URL + "/" + blockId,
                    tokenFor(salon.ownerEmail()));

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(blockExists(UUID.fromString(blockId))).isTrue();
        }

        @Test
        @DisplayName("deleting a block id that never existed is 204")
        void should_return204_when_blockDoesNotExist() throws Exception {
            SalonFixture salon = createSalon();

            ResponseEntity<String> resp = exchange(HttpMethod.DELETE, BLOCKS_URL + "/" + UUID.randomUUID(),
                    tokenFor(salon.ownerEmail()));

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        }
    }

    // ── booking detail fields per viewer ─────────────────────────────────────

    @Nested
    @DisplayName("booking detail block fields")
    class DetailFields {

        @Test
        @DisplayName("salon admin sees the owner's block on the detail: blocked, same id, blockable")
        void should_exposeSharedSalonBlock_when_adminReadsDetail() throws Exception {
            SalonFixture salon = createSalon();
            String adminEmail = unique("admin");
            createUser(adminEmail, "SALON_ADMIN", salon.salonId());
            UUID bookingId = insertBooking(createUser(unique("client"), "CLIENT", null), salon, "COMPLETED");
            String blockId = data(postBlock(tokenFor(salon.ownerEmail()), bookingId)).path("id").asText();

            JsonNode view = detail(bookingId, tokenFor(adminEmail));

            assertThat(view.path("clientBlocked").asBoolean()).isTrue();
            assertThat(view.path("clientBlockId").asText()).isEqualTo(blockId);
            assertThat(view.path("clientBlockable").asBoolean()).isTrue();
        }

        @Test
        @DisplayName("independent master sees an own block on the detail with its id")
        void should_exposeOwnBlock_when_independentMasterReadsDetail() throws Exception {
            String indepEmail = unique("indep");
            IndependentMaster indep = createIndependentMaster(indepEmail);
            UUID bookingId = insertIndependentBooking(createUser(unique("client"), "CLIENT", null), indep.masterId());
            String token = tokenFor(indepEmail);
            String blockId = data(postBlock(token, bookingId)).path("id").asText();

            JsonNode view = detail(bookingId, token);

            assertThat(view.path("clientBlocked").asBoolean()).isTrue();
            assertThat(view.path("clientBlockId").asText()).isEqualTo(blockId);
            assertThat(view.path("clientBlockable").asBoolean()).isTrue();
        }

        @Test
        @DisplayName("unblock flips the detail back to not blocked, still blockable")
        void should_clearBlockState_when_unblocked() throws Exception {
            SalonFixture salon = createSalon();
            UUID bookingId = insertBooking(createUser(unique("client"), "CLIENT", null), salon, "COMPLETED");
            String token = tokenFor(salon.ownerEmail());
            String blockId = data(postBlock(token, bookingId)).path("id").asText();
            exchange(HttpMethod.DELETE, BLOCKS_URL + "/" + blockId, token);

            JsonNode view = detail(bookingId, token);

            assertThat(view.path("clientBlocked").asBoolean()).isFalse();
            assertThat(view.path("clientBlockId").isNull()).isTrue();
            assertThat(view.path("clientBlockable").asBoolean()).isTrue();
        }

        @Test
        @DisplayName("the client viewing their own blocked booking sees false/null/false — the block stays silent")
        void should_hideBlockState_when_clientReadsOwnBlockedBooking() throws Exception {
            SalonFixture salon = createSalon();
            String clientEmail = unique("client");
            UUID bookingId = insertBooking(createUser(clientEmail, "CLIENT", null), salon, "COMPLETED");
            postBlock(tokenFor(salon.ownerEmail()), bookingId);

            ResponseEntity<String> resp = exchange(HttpMethod.GET, BOOKINGS_URL + bookingId, tokenFor(clientEmail));
            JsonNode view = data(resp);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(view.path("clientBlocked").asBoolean()).isFalse();
            assertThat(view.path("clientBlockId").isNull()).isTrue();
            assertThat(view.path("clientBlockable").asBoolean()).isFalse();
        }

        @Test
        @DisplayName("salon master viewing a blocked booking sees false/null/false")
        void should_hideBlockState_when_salonMasterReadsBlockedBooking() throws Exception {
            SalonFixture salon = createSalon();
            UUID bookingId = insertBooking(createUser(unique("client"), "CLIENT", null), salon, "COMPLETED");
            postBlock(tokenFor(salon.ownerEmail()), bookingId);

            JsonNode view = detail(bookingId, tokenFor(salon.masterEmail()));

            assertThat(view.path("clientBlocked").asBoolean()).isFalse();
            assertThat(view.path("clientBlockId").isNull()).isTrue();
            assertThat(view.path("clientBlockable").asBoolean()).isFalse();
        }

        @Test
        @DisplayName("STAFF walk-in of a REGISTERED client is not blockable for the admin, and POST is 400")
        void should_reportNotBlockable_when_staffWalkInOfRegisteredClient() throws Exception {
            SalonFixture salon = createSalon();
            String adminEmail = unique("admin");
            createUser(adminEmail, "SALON_ADMIN", salon.salonId());
            UUID bookingId = insertBooking(createUser(unique("client"), "CLIENT", null), salon, "COMPLETED");
            jdbcTemplate.update("UPDATE bookings SET booking_source = 'STAFF' WHERE id = ?", bookingId);
            String token = tokenFor(adminEmail);

            JsonNode view = detail(bookingId, token);
            ResponseEntity<String> post = postBlock(token, bookingId);

            assertThat(view.path("clientBlockable").asBoolean()).isFalse();
            assertThat(view.path("clientBlocked").asBoolean()).isFalse();
            assertThat(view.path("clientBlockId").isNull()).isTrue();
            assertThat(post.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(countBlocks()).isZero();
        }

        @Test
        @DisplayName("an erased (anonymised) LINK guest is not blockable, and POST is 400")
        void should_reportNotBlockable_when_guestPiiErased() throws Exception {
            SalonFixture salon = createSalon();
            UUID bookingId = insertGuestBooking(salon, "LINK", GUEST_PHONE);
            jdbcTemplate.update("UPDATE bookings SET guest_name = 'Гість', guest_surname = NULL, guest_phone = NULL, "
                    + "guest_pii_anonymised_at = NOW() WHERE id = ?", bookingId);
            String token = tokenFor(salon.ownerEmail());

            JsonNode view = detail(bookingId, token);
            ResponseEntity<String> post = postBlock(token, bookingId);

            assertThat(view.path("clientBlockable").asBoolean()).isFalse();
            assertThat(view.path("clientBlocked").asBoolean()).isFalse();
            assertThat(post.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(countBlocks()).isZero();
        }

        @Test
        @DisplayName("a blocked LINK guest shows blocked + id on the owner's detail")
        void should_exposeGuestBlock_when_ownerReadsGuestDetail() throws Exception {
            SalonFixture salon = createSalon();
            UUID bookingId = insertGuestBooking(salon, "LINK", GUEST_PHONE);
            String token = tokenFor(salon.ownerEmail());
            String blockId = data(postBlock(token, bookingId)).path("id").asText();

            JsonNode view = detail(bookingId, token);

            assertThat(view.path("clientBlocked").asBoolean()).isTrue();
            assertThat(view.path("clientBlockId").asText()).isEqualTo(blockId);
            assertThat(view.path("clientBlockable").asBoolean()).isTrue();
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private JsonNode detail(UUID bookingId, String token) {
        ResponseEntity<String> resp = exchange(HttpMethod.GET, BOOKINGS_URL + bookingId, token);
        assertThat(resp.getStatusCode()).as("detail read must succeed: %s", resp.getBody()).isEqualTo(HttpStatus.OK);
        return data(resp);
    }
}
