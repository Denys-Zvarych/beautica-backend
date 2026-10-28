package com.beautica.moderation;

import com.beautica.common.security.PhoneHasher;
import com.beautica.support.HibernateStatistics;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 397 — client blocks end to end against real Postgres: V210/V211 constraints and cascades, the salon-shared
 * scope (admin A blocks, admin B lists and unblocks), target derivation (registered by account only vs guest by
 * verified phone vs walk-in), no cross-type linking between the two, and the viewer-scoped
 * {@code clientBlocked}/{@code clientBlockId}/{@code clientBlockable} on the provider booking detail. Every token
 * is a real login.
 */
@DisplayName("Client blocks — phase 397 IT")
class ClientBlockIT extends AbstractModerationIT {

    private static final String GUEST_PHONE = "+380501234567";

    @Autowired
    private PhoneHasher phoneHasher;

    @Autowired
    private EntityManagerFactory emf;

    // ── V210/V211 constraints + cascades ────────────────────────────────────

    @Test
    @DisplayName("CHECK rejects a row with both a master and a salon blocker")
    void should_rejectRow_when_twoBlockersSet() {
        SalonFixture salon = createSalon();
        UUID client = createUser(unique("client"), "CLIENT", null);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO client_blocks (id, blocker_master_id, blocker_salon_id, blocked_user_id, display_name) "
                        + "VALUES (?, ?, ?, ?, 'X')",
                UUID.randomUUID(), salon.masterId(), salon.salonId(), client))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_client_blocks_one_blocker");
    }

    @Test
    @DisplayName("CHECK rejects a row with no blocker")
    void should_rejectRow_when_noBlockerSet() {
        UUID client = createUser(unique("client"), "CLIENT", null);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO client_blocks (id, blocked_user_id, display_name) VALUES (?, ?, 'X')",
                UUID.randomUUID(), client))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_client_blocks_one_blocker");
    }

    @Test
    @DisplayName("CHECK rejects a row with neither a blocked user nor a phone hash")
    void should_rejectRow_when_noBlockedIdentity() {
        SalonFixture salon = createSalon();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO client_blocks (id, blocker_salon_id, display_name) VALUES (?, ?, 'X')",
                UUID.randomUUID(), salon.salonId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_client_blocks_blocked_identity");
    }

    @Test
    @DisplayName("V211 CHECK rejects a CLIENT row that also carries a phone hash")
    void should_rejectRow_when_clientRowCarriesPhoneHash() {
        SalonFixture salon = createSalon();
        UUID client = createUser(unique("client"), "CLIENT", null);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO client_blocks (id, blocker_salon_id, blocked_user_id, phone_hash, display_name) "
                        + "VALUES (?, ?, ?, ?, 'X')",
                UUID.randomUUID(), salon.salonId(), client, phoneHasher.hash(GUEST_PHONE).hash()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_client_blocks_identity_kind");
    }

    @Test
    @DisplayName("V211 CHECK rejects masked last digits without a phone hash")
    void should_rejectRow_when_last4WithoutPhoneHash() {
        SalonFixture salon = createSalon();
        UUID client = createUser(unique("client"), "CLIENT", null);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO client_blocks (id, blocker_salon_id, blocked_user_id, phone_last4, display_name) "
                        + "VALUES (?, ?, ?, '4567', 'X')",
                UUID.randomUUID(), salon.salonId(), client))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_client_blocks_last4_needs_hash");
    }

    @Test
    @DisplayName("unique index rejects the same user blocked twice in one salon")
    void should_rejectDuplicate_when_sameUserBlockedTwiceInScope() {
        SalonFixture salon = createSalon();
        UUID client = createUser(unique("client"), "CLIENT", null);
        insertBlockRow(salon.salonId(), client);

        assertThatThrownBy(() -> insertBlockRow(salon.salonId(), client))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ux_client_blocks_salon_user");
    }

    @Test
    @DisplayName("deleting the blocker salon deletes its blocks")
    void should_cascadeDelete_when_salonDeleted() {
        UUID owner = createUser(unique("owner"), "SALON_OWNER", null);
        UUID salonId = insertSalon(owner);
        UUID client = createUser(unique("client"), "CLIENT", null);
        UUID blockId = insertBlockRow(salonId, client);

        jdbcTemplate.update("DELETE FROM salons WHERE id = ?", salonId);

        assertThat(blockExists(blockId)).isFalse();
    }

    @Test
    @DisplayName("deleting the blocked user deletes the block")
    void should_cascadeDelete_when_blockedUserDeleted() {
        SalonFixture salon = createSalon();
        UUID client = createUser(unique("client"), "CLIENT", null);
        UUID blockId = insertBlockRow(salon.salonId(), client);

        jdbcTemplate.update("DELETE FROM users WHERE id = ?", client);

        assertThat(blockExists(blockId)).isFalse();
    }

    @Test
    @DisplayName("deleting the blocker master deletes its blocks")
    void should_cascadeDelete_when_blockerMasterDeleted() {
        IndependentMaster master = createIndependentMaster(unique("indep"));
        UUID client = createUser(unique("client"), "CLIENT", null);
        UUID blockId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO client_blocks (id, blocker_master_id, blocked_user_id, display_name) "
                + "VALUES (?, ?, ?, 'X')", blockId, master.masterId(), client);

        jdbcTemplate.update("DELETE FROM masters WHERE id = ?", master.masterId());

        assertThat(blockExists(blockId)).isFalse();
    }

    // ── salon-shared scope ───────────────────────────────────────────────────

    @Test
    @DisplayName("admin A blocks, admin B of the same salon lists and unblocks it")
    void should_shareSalonBlocks_when_adminABlocksAndAdminBUnblocks() throws Exception {
        SalonFixture salon = createSalon();
        String adminA = unique("admin-a");
        String adminB = unique("admin-b");
        createUser(adminA, "SALON_ADMIN", salon.salonId());
        createUser(adminB, "SALON_ADMIN", salon.salonId());
        UUID client = createUser(unique("client"), "CLIENT", null);
        UUID bookingId = insertBooking(client, salon, "COMPLETED");

        String tokenA = tokenFor(adminA);
        String tokenB = tokenFor(adminB);

        ResponseEntity<String> created = postBlock(tokenA, bookingId);
        ResponseEntity<String> listedByB = exchange(HttpMethod.GET, BLOCKS_URL, tokenB);
        String blockId = data(created).path("id").asText();
        ResponseEntity<String> deleted = exchange(HttpMethod.DELETE, BLOCKS_URL + "/" + blockId, tokenB);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(data(listedByB)).hasSize(1);
        assertThat(data(listedByB).get(0).path("id").asText()).isEqualTo(blockId);
        assertThat(data(listedByB).get(0).path("kind").asText()).isEqualTo("CLIENT");
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(blockExists(UUID.fromString(blockId))).isFalse();
    }

    @Test
    @DisplayName("a repeated owner block is 200 with the same row")
    void should_return200SameRow_when_blockRepeated() throws Exception {
        SalonFixture salon = createSalon();
        UUID client = createUser(unique("client"), "CLIENT", null);
        UUID bookingId = insertBooking(client, salon, "COMPLETED");
        String token = tokenFor(salon.ownerEmail());

        ResponseEntity<String> first = postBlock(token, bookingId);
        ResponseEntity<String> second = postBlock(token, bookingId);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(second).path("id").asText()).isEqualTo(data(first).path("id").asText());
        assertThat(countBlocks()).isEqualTo(1);
    }

    @Test
    @DisplayName("salon master POST /blocks is 403 and stores nothing")
    void should_return403_when_salonMasterBlocks() throws Exception {
        SalonFixture salon = createSalon();
        UUID bookingId = insertBooking(createUser(unique("client"), "CLIENT", null), salon, "COMPLETED");

        ResponseEntity<String> resp = postBlock(tokenFor(salon.masterEmail()), bookingId);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(countBlocks()).isZero();
    }

    @Test
    @DisplayName("owner blocking another salon's booking gets 404")
    void should_return404_when_bookingOfAnotherSalon() throws Exception {
        SalonFixture mine = createSalon();
        SalonFixture other = createSalon();
        UUID bookingId = insertBooking(createUser(unique("client"), "CLIENT", null), other, "COMPLETED");

        ResponseEntity<String> resp = postBlock(tokenFor(mine.ownerEmail()), bookingId);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("owner blocking on an inactive salon gets 404 and stores nothing")
    void should_return404_when_ownerBlocksOnInactiveSalon() throws Exception {
        SalonFixture salon = createSalon();
        UUID bookingId = insertBooking(createUser(unique("client"), "CLIENT", null), salon, "COMPLETED");
        String token = tokenFor(salon.ownerEmail());
        jdbcTemplate.update("UPDATE salons SET is_active = false WHERE id = ?", salon.salonId());

        ResponseEntity<String> resp = postBlock(token, bookingId);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(countBlocks()).isZero();
    }

    // ── target derivation ────────────────────────────────────────────────────

    @Test
    @DisplayName("registered client is blocked by account only — no phone hash, no last4")
    void should_storeUserOnlyWithoutPhone_when_registeredClient() throws Exception {
        SalonFixture salon = createSalon();
        UUID client = createUser(unique("client"), "CLIENT", null);
        jdbcTemplate.update("UPDATE users SET phone_number = '0671112233' WHERE id = ?", client);
        UUID bookingId = insertBooking(client, salon, "COMPLETED");

        ResponseEntity<String> resp = postBlock(tokenFor(salon.ownerEmail()), bookingId);

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM client_blocks");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(row.get("blocked_user_id")).isEqualTo(client);
        assertThat(row.get("phone_hash")).as("a registered client's profile phone is never hashed").isNull();
        assertThat(row.get("phone_last4")).isNull();
        assertThat(row.get("blocker_salon_id")).isEqualTo(salon.salonId());
        assertThat(row.get("blocker_master_id")).isNull();
        assertThat(data(resp).path("maskedPhone").isNull()).isTrue();
    }

    @Test
    @DisplayName("LINK guest is blocked by verified phone hash + last4, never echoed raw")
    void should_storePhoneHashOnly_when_guestBooking() throws Exception {
        SalonFixture salon = createSalon();
        UUID bookingId = insertGuestBooking(salon, "LINK", GUEST_PHONE);

        ResponseEntity<String> resp = postBlock(tokenFor(salon.ownerEmail()), bookingId);

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM client_blocks");
        assertThat(row.get("blocked_user_id")).isNull();
        assertThat((byte[]) row.get("phone_hash")).hasSize(32)
                .isEqualTo(phoneHasher.hash(GUEST_PHONE).hash());
        assertThat(row.get("phone_last4")).isEqualTo("4567");
        assertThat(row.get("display_name")).isEqualTo("Марія Ш.");
        assertThat(data(resp).path("kind").asText()).isEqualTo("GUEST");
        assertThat(data(resp).path("maskedPhone").asText()).isEqualTo("+380 •• ••• 45 67");
        assertThat(resp.getBody()).doesNotContain(GUEST_PHONE).doesNotContain("phoneHash");
    }

    @Test
    @DisplayName("STAFF walk-in POST /blocks is 400 and stores nothing")
    void should_return400_when_staffWalkIn() throws Exception {
        SalonFixture salon = createSalon();
        UUID bookingId = insertGuestBooking(salon, "STAFF", GUEST_PHONE);

        ResponseEntity<String> resp = postBlock(tokenFor(salon.ownerEmail()), bookingId);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(countBlocks()).isZero();
    }

    // ── no cross-type linking (user decision 2026-10-28) ─────────────────────

    @Test
    @DisplayName("a CLIENT block never blocks a guest who shares the profile phone")
    void should_notLinkGuestBookingToClientBlock_when_profilePhoneEqualsGuestPhone() throws Exception {
        SalonFixture salon = createSalon();
        UUID client = createUser(unique("client"), "CLIENT", null);
        jdbcTemplate.update("UPDATE users SET phone_number = ? WHERE id = ?", GUEST_PHONE, client);
        String token = tokenFor(salon.ownerEmail());
        postBlock(token, insertBooking(client, salon, "COMPLETED"));
        UUID guestBooking = insertGuestBooking(salon, "LINK", GUEST_PHONE);

        JsonNode guestView = data(exchange(HttpMethod.GET, "/api/v1/bookings/" + guestBooking, token));
        ResponseEntity<String> guestBlock = postBlock(token, guestBooking);

        assertThat(guestView.path("clientBlockable").asBoolean()).isTrue();
        assertThat(guestView.path("clientBlocked").asBoolean())
                .as("the CLIENT row must not block a guest who merely shares the profile phone")
                .isFalse();
        assertThat(guestView.path("clientBlockId").isNull()).isTrue();
        assertThat(guestBlock.getStatusCode()).as("a separate GUEST row, not a repeat").isEqualTo(HttpStatus.CREATED);
        assertThat(countBlocks()).isEqualTo(2);
    }

    @Test
    @DisplayName("a GUEST block never absorbs an account whose profile phone matches")
    void should_notMergeIntoGuestRow_when_clientProfilePhoneMatches() throws Exception {
        SalonFixture salon = createSalon();
        String token = tokenFor(salon.ownerEmail());
        String guestBlockId = data(postBlock(token, insertGuestBooking(salon, "LINK", GUEST_PHONE))).path("id").asText();
        UUID client = createUser(unique("client"), "CLIENT", null);
        jdbcTemplate.update("UPDATE users SET phone_number = ? WHERE id = ?", GUEST_PHONE, client);
        UUID appBooking = insertBooking(client, salon, "COMPLETED");

        JsonNode appViewBefore = data(exchange(HttpMethod.GET, "/api/v1/bookings/" + appBooking, token));
        ResponseEntity<String> clientBlock = postBlock(token, appBooking);

        assertThat(appViewBefore.path("clientBlocked").asBoolean())
                .as("the GUEST row must not block an account whose profile phone matches")
                .isFalse();
        assertThat(clientBlock.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(data(clientBlock).path("id").asText()).isNotEqualTo(guestBlockId);
        assertThat(data(clientBlock).path("kind").asText()).isEqualTo("CLIENT");
        Map<String, Object> guestRow = jdbcTemplate.queryForMap(
                "SELECT blocked_user_id, phone_hash FROM client_blocks WHERE id = ?", UUID.fromString(guestBlockId));
        assertThat(guestRow.get("blocked_user_id")).as("the guest row is never filled with the account").isNull();
        assertThat((byte[]) guestRow.get("phone_hash")).isEqualTo(phoneHasher.hash(GUEST_PHONE).hash());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT phone_hash IS NULL FROM client_blocks WHERE blocked_user_id = ?", Boolean.class, client))
                .isTrue();
    }

    // ── GET /blocks cost ─────────────────────────────────────────────────────

    /**
     * Perf audit — the list is one projection statement whatever the row count: 1 row and 10 rows (CLIENT and
     * GUEST mixed) cost the same. Each token is used once first so the JWT filter's cache is warm.
     */
    @Test
    @DisplayName("GET /blocks costs the same statements for 1 and 10 rows, no entity hydrated")
    void should_costSameStatements_when_listingOneOrTenBlocks() throws Exception {
        SalonFixture small = createSalon();
        SalonFixture large = createSalon();
        insertBlockRow(small.salonId(), createUser(unique("client"), "CLIENT", null));
        for (int i = 0; i < 5; i++) {
            insertBlockRow(large.salonId(), createUser(unique("client"), "CLIENT", null));
            insertGuestBlockRow(large.salonId(), "+38050000000" + i);
        }
        String smallToken = tokenFor(small.ownerEmail());
        String largeToken = tokenFor(large.ownerEmail());
        exchange(HttpMethod.GET, BLOCKS_URL, smallToken);
        exchange(HttpMethod.GET, BLOCKS_URL, largeToken);
        Statistics statistics = HibernateStatistics.enabledOn(emf);

        statistics.clear();
        ResponseEntity<String> one = exchange(HttpMethod.GET, BLOCKS_URL, smallToken);
        long oneRowStatements = statistics.getPrepareStatementCount();
        long oneRowEntities = statistics.getEntityLoadCount();
        statistics.clear();
        ResponseEntity<String> ten = exchange(HttpMethod.GET, BLOCKS_URL, largeToken);
        long tenRowStatements = statistics.getPrepareStatementCount();
        long tenRowEntities = statistics.getEntityLoadCount();

        assertThat(data(one)).hasSize(1);
        assertThat(data(ten)).hasSize(10);
        assertThat(tenRowStatements)
                .as("1 row: %s statements, 10 rows: %s — any growth is an N+1", oneRowStatements, tenRowStatements)
                .isEqualTo(oneRowStatements)
                .isEqualTo(LIST_STATEMENTS);
        assertThat(tenRowEntities)
                .as("constructor projection — no ClientBlock or User entity is hydrated")
                .isEqualTo(oneRowEntities)
                .isZero();
    }

    /** Owner list: the active-salon id lookup + the one projection query. */
    private static final long LIST_STATEMENTS = 2L;

    // ── provider booking detail ──────────────────────────────────────────────

    @Test
    @DisplayName("booking detail shows the block only to the blocking scope")
    void should_exposeBlockStateOnlyForBlockingScope_when_readingBookingDetail() throws Exception {
        SalonFixture salon = createSalon();
        IndependentMaster indep = createIndependentMaster(unique("indep"));
        UUID client = createUser(unique("client"), "CLIENT", null);
        UUID salonBooking = insertBooking(client, salon, "COMPLETED");
        UUID indepBooking = insertIndependentBooking(client, indep.masterId());
        String ownerToken = tokenFor(salon.ownerEmail());
        String indepEmail = jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class,
                indep.userId());
        String blockId = data(postBlock(ownerToken, salonBooking)).path("id").asText();

        JsonNode ownerView = data(exchange(HttpMethod.GET, "/api/v1/bookings/" + salonBooking, ownerToken));
        JsonNode indepView = data(exchange(HttpMethod.GET, "/api/v1/bookings/" + indepBooking, tokenFor(indepEmail)));
        JsonNode masterView = data(exchange(HttpMethod.GET, "/api/v1/bookings/" + salonBooking,
                tokenFor(salon.masterEmail())));

        assertThat(ownerView.path("clientBlocked").asBoolean()).isTrue();
        assertThat(ownerView.path("clientBlockId").asText()).isEqualTo(blockId);
        assertThat(ownerView.path("clientBlockable").asBoolean()).isTrue();
        assertThat(indepView.path("clientBlocked").asBoolean()).isFalse();
        assertThat(indepView.path("clientBlockId").isNull()).isTrue();
        assertThat(indepView.path("clientBlockable").asBoolean()).isTrue();
        assertThat(masterView.path("clientBlocked").asBoolean()).isFalse();
        assertThat(masterView.path("clientBlockable").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("booking detail of a STAFF walk-in is not blockable")
    void should_reportNotBlockable_when_detailOfStaffWalkIn() throws Exception {
        SalonFixture salon = createSalon();
        UUID bookingId = insertGuestBooking(salon, "STAFF", GUEST_PHONE);

        JsonNode view = data(exchange(HttpMethod.GET, "/api/v1/bookings/" + bookingId,
                tokenFor(salon.ownerEmail())));

        assertThat(view.path("clientBlockable").asBoolean()).isFalse();
        assertThat(view.path("clientBlocked").asBoolean()).isFalse();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private UUID insertBlockRow(UUID salonId, UUID userId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO client_blocks (id, blocker_salon_id, blocked_user_id, display_name) "
                + "VALUES (?, ?, ?, 'X')", id, salonId, userId);
        return id;
    }

    private void insertGuestBlockRow(UUID salonId, String phone) {
        jdbcTemplate.update("INSERT INTO client_blocks (id, blocker_salon_id, phone_hash, phone_last4, display_name) "
                        + "VALUES (?, ?, ?, ?, 'Гість Г.')",
                UUID.randomUUID(), salonId, phoneHasher.hash(phone).hash(), phone.substring(phone.length() - 4));
    }
}
