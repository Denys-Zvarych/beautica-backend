package com.beautica.salon;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.salon.dto.SiblingSalonOption;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 21.3b — integration tests for {@code GET /api/v1/salons/{salonId}/sibling-salons}.
 *
 * <p>Mirrors {@link SalonStaffEndpointIT} (the closest analogue — same management gate, same
 * {@code List<T>} shape): real HTTP through {@link TestRestTemplate} against a Testcontainers
 * PostgreSQL instance, fixtures inserted directly via JDBC ({@link SalonItFixtures}), cleanup by
 * {@link AbstractIntegrationTest#cleanDb()}.
 *
 * <p>This endpoint feeds the rotate-admin destination picker, so its result set must be the exact
 * complement of what {@code PATCH /{salonId}/admins/{userId}/salon} accepts. The three tests that
 * pin that are: self is excluded (a same-salon rotation is a 400 no-op), an inactive sibling is
 * excluded (an inactive destination is a 403), and another owner's salon is excluded (a cross-owner
 * destination is a 403). The last of those runs alongside a legitimate sibling in the same fixture,
 * so a query that dropped the owner predicate cannot pass it.
 */
@Import(TestSecurityConfig.class)
@DisplayName("SalonController.getSiblingSalons — sibling-salons endpoint (Phase 21.3b)")
class SalonSiblingSalonsEndpointIT extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(SalonSiblingSalonsEndpointIT.class);

    private static final String SIBLINGS_URL = "/api/v1/salons/%s/sibling-salons";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private SalonItFixtures fixtures;

    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
        fixtures = new SalonItFixtures(
                restTemplate, jdbcTemplate, objectMapper, passwordEncoder, this::testCityId);
    }

    @Test
    @DisplayName("200 listing the owner's other active salons and EXCLUDING the source salon itself")
    void should_excludeSourceSalon_when_ownerListsSiblings() throws Exception {
        // Arrange — the source salon must NOT appear: rotating an admin into the salon they already
        // occupy is the no-op that SalonService.rotateAdmin rejects with 400.
        UUID ownerId = fixtures.insertUser("owner-siblings-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID sourceSalonId = fixtures.insertSalon(ownerId, "Source Salon");
        UUID siblingOneId = fixtures.insertSalon(ownerId, "Sibling One");
        UUID siblingTwoId = fixtures.insertSalon(ownerId, "Sibling Two");
        String ownerToken = fixtures.loginAndGetToken(fixtures.emailOf(ownerId));

        // Act
        log.debug("Act: GET {} as SALON_OWNER — must list the two siblings, never the source",
                String.format(SIBLINGS_URL, sourceSalonId));
        ResponseEntity<String> response = get(sourceSalonId, ownerToken);

        // Assert — containsExactlyInAnyOrder is already an exhaustive set assertion, so a trailing
        // doesNotContain(sourceSalonId) could never fail independently of it (it was removed).
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dataOf(response))
                .as("the source salon is a rotation DESTINATION no-op — it must be excluded, and "
                        + "both of the owner's other active salons must be present")
                .extracting(SiblingSalonOption::id)
                .containsExactlyInAnyOrder(siblingOneId, siblingTwoId);
    }

    @Test
    @DisplayName("200 when the salon's OWN SALON_ADMIN lists siblings — the caller GET /salons/mine 403s")
    void should_return200_when_ownAdminListsSiblings() throws Exception {
        // Arrange — this is the whole reason the endpoint exists: an admin performing a rotation
        // cannot call GET /salons/mine (hasRole('SALON_OWNER') only).
        UUID ownerId = fixtures.insertUser("owner-admview-sib-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID sourceSalonId = fixtures.insertSalon(ownerId, "Admin Source Salon");
        UUID siblingId = fixtures.insertSalon(ownerId, "Admin Sibling Salon");
        UUID adminUserId = fixtures.insertAdmin("admin-sib-" + System.nanoTime() + "@beautica.test", sourceSalonId);
        String adminToken = fixtures.loginAndGetToken(fixtures.emailOf(adminUserId));

        // Act
        log.debug("Act: GET {} as the salon's own SALON_ADMIN — must succeed",
                String.format(SIBLINGS_URL, sourceSalonId));
        ResponseEntity<String> response = get(sourceSalonId, adminToken);

        // Assert
        assertThat(response.getStatusCode())
                .as("an admin assigned to the salon must be allowed to enumerate rotation destinations")
                .isEqualTo(HttpStatus.OK);
        assertThat(dataOf(response))
                .extracting(SiblingSalonOption::id)
                .containsExactly(siblingId);

        // Assert — the endpoint this one replaces still denies the same caller
        ResponseEntity<String> mineResponse = restTemplate.exchange(
                "/api/v1/salons/mine", HttpMethod.GET,
                new HttpEntity<>(fixtures.bearerHeaders(adminToken)), String.class);
        assertThat(mineResponse.getStatusCode())
                .as("GET /salons/mine must stay SALON_OWNER-only — this endpoint exists because it 403s here")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("a deactivated sibling is excluded while the active sibling remains")
    void should_excludeInactiveSibling_when_siblingHasBeenDeactivated() throws Exception {
        // Arrange — an inactive destination is a 403 on rotateAdmin, so it must never be offered.
        UUID ownerId = fixtures.insertUser("owner-inactive-sib-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID sourceSalonId = fixtures.insertSalon(ownerId, "Inactive Source Salon");
        UUID activeSiblingId = fixtures.insertSalon(ownerId, "Active Sibling");
        fixtures.insertSalon(ownerId, "Deactivated Sibling", false);
        String ownerToken = fixtures.loginAndGetToken(fixtures.emailOf(ownerId));

        // Act
        log.debug("Act: GET {} — owner has one active and one deactivated sibling",
                String.format(SIBLINGS_URL, sourceSalonId));
        ResponseEntity<String> response = get(sourceSalonId, ownerToken);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dataOf(response))
                .as("a soft-deleted salon is never a legal rotation destination — it must be filtered out")
                .extracting(SiblingSalonOption::id)
                .containsExactly(activeSiblingId);
    }

    @Test
    @DisplayName("another owner's active salon is excluded even when a legitimate sibling exists")
    void should_excludeForeignOwnersSalon_when_listingSiblings() throws Exception {
        // Arrange — a legitimate sibling is present in the SAME fixture, so a query that dropped the
        // owner predicate would return three rows and fail, rather than trivially returning none.
        UUID ownerId = fixtures.insertUser("owner-scoped-sib-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID sourceSalonId = fixtures.insertSalon(ownerId, "Scoped Source Salon");
        UUID ownSiblingId = fixtures.insertSalon(ownerId, "Scoped Own Sibling");
        UUID foreignOwnerId = fixtures.insertUser("owner-foreign-sib-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        fixtures.insertSalon(foreignOwnerId, "Foreign Active Salon");
        String ownerToken = fixtures.loginAndGetToken(fixtures.emailOf(ownerId));

        // Act
        log.debug("Act: GET {} — a different owner also has an active salon",
                String.format(SIBLINGS_URL, sourceSalonId));
        ResponseEntity<String> response = get(sourceSalonId, ownerToken);

        // Assert — containsExactly is exhaustive, so the foreign salon's absence is already covered;
        // a trailing doesNotContain(foreignSalonId) could not fail independently and was removed.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<SiblingSalonOption> siblings = dataOf(response);
        assertThat(siblings)
                .as("cross-owner rotation is a 403 — another owner's salon must never be listed")
                .extracting(SiblingSalonOption::id)
                .containsExactly(ownSiblingId);

        // Assert — the owner scoping is verified against the DB, not the wire: SiblingSalonOption
        // deliberately carries no ownerId (the picker has no use for the owner's user UUID), so the
        // same-owner guarantee is checked where it actually lives, on the salons row.
        assertThat(siblings)
                .as("every listed sibling must belong to the SOURCE salon's owner")
                .allSatisfy(s -> assertThat(ownerIdOf(s.id())).isEqualTo(ownerId));
    }

    @Test
    @DisplayName("the picker row carries id + name + short address, and nothing more")
    void should_exposeOnlyPickerFields_when_listingSiblings() throws Exception {
        // Arrange — the response was narrowed from SalonResponse (Security LOW / Perf LOW-1): an
        // assigned SALON_ADMIN must not receive the owner's UUID, nor the phone/instagram/avatar/
        // description/isPrimary/createdAt of salons they hold no assignment to. Asserting the raw
        // JSON keys is the only way to catch a widened DTO — a typed read would silently ignore
        // extra fields.
        UUID ownerId = fixtures.insertUser("owner-shape-sib-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID sourceSalonId = fixtures.insertSalon(ownerId, "Shape Source Salon");
        UUID siblingId = fixtures.insertSalon(ownerId, "Shape Sibling Salon");
        jdbcTemplate.update(
                "UPDATE salons SET street = ?, building_no = ?, phone = ?, description = ? WHERE id = ?",
                "вул. Хрещатик", "12Б", "+380501112233", "internal description", siblingId);
        UUID adminUserId = fixtures.insertAdmin("admin-shape-" + System.nanoTime() + "@beautica.test", sourceSalonId);
        String adminToken = fixtures.loginAndGetToken(fixtures.emailOf(adminUserId));

        // Act
        log.debug("Act: GET {} as the salon's SALON_ADMIN — inspect the raw row keys",
                String.format(SIBLINGS_URL, sourceSalonId));
        ResponseEntity<String> response = get(sourceSalonId, adminToken);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var row = objectMapper.readTree(response.getBody()).get("data").get(0);
        assertThat(row.fieldNames())
                .toIterable()
                .as("the picker row is exactly id + name + short address — any other key means the "
                        + "response widened back towards SalonResponse")
                .containsExactlyInAnyOrder("id", "name", "street", "buildingNo");
        assertThat(dataOf(response))
                .singleElement()
                .as("the four fields the picker DOES need must all carry their persisted values")
                .isEqualTo(new SiblingSalonOption(siblingId, "Shape Sibling Salon", "вул. Хрещатик", "12Б"));
    }

    @Test
    @DisplayName("200 with an empty list when the owner has no other salon")
    void should_returnEmptyList_when_ownerHasOnlyThisSalon() throws Exception {
        // Arrange
        UUID ownerId = fixtures.insertUser("owner-lonely-sib-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID sourceSalonId = fixtures.insertSalon(ownerId, "Lonely Salon");
        String ownerToken = fixtures.loginAndGetToken(fixtures.emailOf(ownerId));

        // Act
        log.debug("Act: GET {} — this owner has exactly one salon",
                String.format(SIBLINGS_URL, sourceSalonId));
        ResponseEntity<String> response = get(sourceSalonId, ownerToken);

        // Assert
        assertThat(response.getStatusCode())
                .as("a single-salon owner must get 200 with an empty picker, not an error")
                .isEqualTo(HttpStatus.OK);
        assertThat(dataOf(response)).isEmpty();
    }

    @Test
    @DisplayName("siblings come back ordered by created_at ASC — oldest salon first in the picker")
    void should_orderSiblingsByCreatedAtAscending_when_ownerHasSeveralSalons() throws Exception {
        // Arrange — created_at values are minutes apart, so this test pins the PRIMARY sort key on
        // its own; the id tiebreaker is exercised separately by the equal-timestamp test below.
        // Rows are INSERTED in an order deliberately different from their timestamp order, so the
        // assertion cannot pass on insertion order alone.
        UUID ownerId = fixtures.insertUser("owner-order-sib-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID sourceSalonId = fixtures.insertSalonAgedMinutes(ownerId, "Order Source Salon", 40);
        UUID newestSiblingId = fixtures.insertSalonAgedMinutes(ownerId, "Order Newest Sibling", 5);
        UUID oldestSiblingId = fixtures.insertSalonAgedMinutes(ownerId, "Order Oldest Sibling", 30);
        UUID middleSiblingId = fixtures.insertSalonAgedMinutes(ownerId, "Order Middle Sibling", 15);
        String ownerToken = fixtures.loginAndGetToken(fixtures.emailOf(ownerId));

        // Act
        log.debug("Act: GET {} — three siblings created 30, 15 and 5 minutes ago, inserted out of order",
                String.format(SIBLINGS_URL, sourceSalonId));
        ResponseEntity<String> response = get(sourceSalonId, ownerToken);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dataOf(response))
                .as("the picker's row order is part of its contract — oldest salon first, matching "
                        + "GET /salons/mine and the idx_salons_owner_active_created index")
                .extracting(SiblingSalonOption::id)
                .containsExactly(oldestSiblingId, middleSiblingId, newestSiblingId);
    }

    @Test
    @DisplayName("siblings sharing one created_at fall back to the id tiebreaker, stably across calls")
    void should_orderSiblingsByIdTiebreaker_when_createdAtValuesAreIdentical() throws Exception {
        // Arrange — the failure this pins (Perf MEDIUM-2): ORDER BY created_at alone is not a total
        // order, so salons written in the same statement/tick came back in whatever order the plan
        // happened to produce. Every sibling here shares one exact timestamp, so the id tiebreaker
        // is the ONLY thing that can decide the order.
        //
        // The six ids are INSERTED in descending order, so without the tiebreaker the rows are
        // returned in their heap/insertion order — the exact REVERSE of what is asserted. That
        // makes the test deterministically red when `, s.id` is dropped, rather than relying on six
        // random UUIDs happening to land out of order (a 1-in-720 vacuous pass).
        UUID ownerId = fixtures.insertUser("owner-tie-sib-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID sourceSalonId = fixtures.insertSalon(ownerId, "Tie Source Salon");
        // Sorted by STRING, not by UUID.compareTo: Java compares the two longs SIGNED, so a uuid
        // whose top bit is set sorts first in Java and last in Postgres, which orders the type
        // byte-wise UNSIGNED. Canonical lowercase hex is ASCII-ordered identically to those bytes,
        // so String order is the DB's order. (Sorting with UUID::compareTo here made this test fail
        // against the correct query — the mismatch is real, not theoretical.)
        List<UUID> ascendingIds = java.util.stream.Stream.generate(UUID::randomUUID).limit(6)
                .sorted(java.util.Comparator.comparing(UUID::toString)).toList();
        for (UUID id : ascendingIds.reversed()) {
            jdbcTemplate.update(
                    "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at, city_id) "
                            + "VALUES (?, ?, ?, true, TIMESTAMPTZ '2026-01-01 09:00:00+00', NOW(), ?)",
                    id, ownerId, "Tie Sibling " + id, testCityId());
        }
        jdbcTemplate.update(
                "UPDATE salons SET created_at = TIMESTAMPTZ '2026-01-01 09:00:00+00' WHERE id = ?",
                sourceSalonId);
        String ownerToken = fixtures.loginAndGetToken(fixtures.emailOf(ownerId));

        // Act
        log.debug("Act: GET {} twice — six siblings share one created_at to the microsecond, "
                        + "inserted in DESCENDING id order", String.format(SIBLINGS_URL, sourceSalonId));
        List<SiblingSalonOption> first = dataOf(get(sourceSalonId, ownerToken));
        List<SiblingSalonOption> second = dataOf(get(sourceSalonId, ownerToken));

        // Assert
        assertThat(first)
                .as("with created_at tied, the unique id tiebreaker must impose the total order — "
                        + "ascending, i.e. the reverse of the order the rows were written in")
                .extracting(SiblingSalonOption::id)
                .containsExactlyElementsOf(ascendingIds);
        assertThat(second)
                .as("and it must be the SAME order on a repeat call — a non-deterministic picker "
                        + "reorders itself under the user's finger")
                .extracting(SiblingSalonOption::id)
                .containsExactlyElementsOf(ascendingIds);
    }

    @Test
    @DisplayName("403 when a SALON_MASTER of THIS VERY SALON lists its siblings")
    void should_return403_when_salonMasterOfThisSalonListsSiblings() throws Exception {
        // Arrange — the denied actor is assigned to the salon in the path, so this is a pure ROLE
        // denial: only SALON_OWNER/SALON_ADMIN may enumerate an owner's portfolio, and a read-only
        // master must not learn which other salons the owner runs. A master of a DIFFERENT salon
        // would be denied by salon scoping and so would not exercise the role half of the gate.
        UUID ownerId = fixtures.insertUser("owner-master-sib-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = fixtures.insertSalon(ownerId, "Master Denied Sibling Salon");
        fixtures.insertSalon(ownerId, "Master Denied Sibling Two");
        UUID masterUserId = fixtures.insertSalonMasterUser(
                "master-sib-" + System.nanoTime() + "@beautica.test", salonId);
        String masterToken = fixtures.loginAndGetToken(fixtures.emailOf(masterUserId));

        // Act
        log.debug("Act: GET {} as the salon's OWN SALON_MASTER — role guard must still deny",
                String.format(SIBLINGS_URL, salonId));
        ResponseEntity<String> response = get(salonId, masterToken);

        // Assert
        assertThat(response.getStatusCode())
                .as("SALON_MASTER is read-only staff — being assigned to the salon must not grant "
                        + "portfolio enumeration")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("403 when a foreign SALON_OWNER lists another salon's siblings")
    void should_return403_when_foreignOwnerListsSiblings() throws Exception {
        // Arrange
        UUID ownerAId = fixtures.insertUser("owner-a-sib-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonAId = fixtures.insertSalon(ownerAId, "Salon A Siblings");
        fixtures.insertSalon(ownerAId, "Salon A Sibling Two");
        UUID ownerBId = fixtures.insertUser("owner-b-sib-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        fixtures.insertSalon(ownerBId, "Salon B Siblings");
        String ownerBToken = fixtures.loginAndGetToken(fixtures.emailOf(ownerBId));

        // Act
        log.debug("Act: GET {} as a foreign SALON_OWNER — must be denied",
                String.format(SIBLINGS_URL, salonAId));
        ResponseEntity<String> response = get(salonAId, ownerBToken);

        // Assert
        assertThat(response.getStatusCode())
                .as("a foreign owner must not be able to enumerate another owner's salon portfolio")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("403 when a SALON_ADMIN of a different salon lists these siblings")
    void should_return403_when_foreignAdminListsSiblings() throws Exception {
        // Arrange
        UUID ownerAId = fixtures.insertUser("owner-a-admsib-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonAId = fixtures.insertSalon(ownerAId, "Salon A Admin Siblings");
        fixtures.insertSalon(ownerAId, "Salon A Admin Sibling Two");
        UUID ownerBId = fixtures.insertUser("owner-b-admsib-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonBId = fixtures.insertSalon(ownerBId, "Salon B Admin Siblings");
        UUID adminBId = fixtures.insertAdmin("admin-b-sib-" + System.nanoTime() + "@beautica.test", salonBId);
        String adminBToken = fixtures.loginAndGetToken(fixtures.emailOf(adminBId));

        // Act
        log.debug("Act: GET {} as a SALON_ADMIN assigned to a DIFFERENT salon — must be denied",
                String.format(SIBLINGS_URL, salonAId));
        ResponseEntity<String> response = get(salonAId, adminBToken);

        // Assert
        assertThat(response.getStatusCode())
                .as("an admin not assigned to this salon must be denied")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("403 when a CLIENT lists a salon's siblings")
    void should_return403_when_clientListsSiblings() throws Exception {
        // Arrange
        UUID ownerId = fixtures.insertUser("owner-client-sib-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = fixtures.insertSalon(ownerId, "Client Denied Sibling Salon");
        fixtures.insertSalon(ownerId, "Client Denied Sibling Two");
        UUID clientId = fixtures.insertUser("client-sib-" + System.nanoTime() + "@beautica.test", "CLIENT");
        String clientToken = fixtures.loginAndGetToken(fixtures.emailOf(clientId));

        // Act
        log.debug("Act: GET {} as CLIENT — role guard must deny with 403",
                String.format(SIBLINGS_URL, salonId));
        ResponseEntity<String> response = get(salonId, clientToken);

        // Assert
        assertThat(response.getStatusCode())
                .as("CLIENT must be denied — this is not a public endpoint")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("401 when the request carries no bearer token")
    void should_return401_when_unauthenticated() {
        // Arrange
        UUID ownerId = fixtures.insertUser("owner-anon-sib-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = fixtures.insertSalon(ownerId, "Anon Denied Sibling Salon");
        fixtures.insertSalon(ownerId, "Anon Denied Sibling Two");

        // Act
        log.debug("Act: GET {} unauthenticated — must not be permitAll",
                String.format(SIBLINGS_URL, salonId));
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(SIBLINGS_URL, salonId), HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("the sibling picker exposes a whole owner portfolio — it must require auth, "
                        + "unlike the permitAll GET /salons/{salonId}")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private ResponseEntity<String> get(UUID salonId, String token) {
        return restTemplate.exchange(
                String.format(SIBLINGS_URL, salonId), HttpMethod.GET,
                new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);
    }

    private List<SiblingSalonOption> dataOf(ResponseEntity<String> response) throws Exception {
        return objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<List<SiblingSalonOption>>>() {}).data();
    }

    /** The persisted {@code salons.owner_id} — the scoping the wire DTO no longer surfaces. */
    private UUID ownerIdOf(UUID salonId) {
        return jdbcTemplate.queryForObject(
                "SELECT owner_id FROM salons WHERE id = ?", UUID.class, salonId);
    }
}
