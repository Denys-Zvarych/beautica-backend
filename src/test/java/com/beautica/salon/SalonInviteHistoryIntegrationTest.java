package com.beautica.salon;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.salon.dto.SalonInviteHistoryResponse;
import com.beautica.salon.dto.SalonInviteResponse;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@code GET /api/v1/salons/{salonId}/invites} (the FULL invite history)
 * and {@code DELETE /api/v1/salons/{salonId}/invites/{inviteId}}.
 *
 * <p>Replaces {@code PendingInvitesIntegrationTest}: the endpoint no longer filters to
 * unused-and-unexpired rows, so the old class's central assertion ("expired and used invites are
 * excluded") asserted the exact opposite of the shipped contract and could not be salvaged. Its
 * cancel/DELETE coverage is carried over unchanged.
 *
 * <p>Mirrors {@code SalonAdminRemovalIntegrationTest}: real HTTP through {@link TestRestTemplate}
 * against a Testcontainers PostgreSQL instance, fixtures inserted directly via JDBC. Cleanup is
 * handled by {@link AbstractIntegrationTest#cleanDb()} (already deletes {@code invite_tokens}).
 *
 * <p>Fixture data is deliberately neutral (no real localities); {@code testCityId()} supplies the
 * single seeded city every salon fixture in this suite uses.
 */
@Import(TestSecurityConfig.class)
@DisplayName("SalonController — salon invite history endpoints")
class SalonInviteHistoryIntegrationTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(SalonInviteHistoryIntegrationTest.class);

    private static final String HISTORY_URL = "/api/v1/salons/%s/invites";
    private static final String CANCEL_URL = "/api/v1/salons/%s/invites/%s";
    private static final String TEST_PASSWORD = "Str0ngP@ss1!";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
    }

    // ── GET /invites — the history contract ───────────────────────────────────

    /**
     * The headline contract: all four derived statuses reach the wire, from four rows that differ
     * ONLY in the columns the ladder reads. The predecessor endpoint returned exactly one of these
     * four rows.
     */
    @Test
    @DisplayName("GET /invites — 200 returning ALL FOUR statuses (PENDING, ACCEPTED, EXPIRED, "
            + "CANCELLED), each derived from the row's own columns")
    void should_returnEveryStatus_when_ownerListsAMixedHistory() throws Exception {
        // Arrange
        UUID ownerId = insertUser("owner-statuses-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = insertSalon(ownerId, "All Statuses Salon");

        UUID pendingId = insertInvite("pending@beautica.test", salonId, "SALON_MASTER",
                Instant.now().plus(7, ChronoUnit.DAYS), false, null, hoursAgo(1));
        UUID acceptedId = insertInvite("accepted@beautica.test", salonId, "SALON_ADMIN",
                Instant.now().plus(7, ChronoUnit.DAYS), true, null, hoursAgo(2));
        UUID lapsedId = insertInvite("lapsed@beautica.test", salonId, "SALON_MASTER",
                Instant.now().minus(1, ChronoUnit.DAYS), false, null, hoursAgo(3));
        UUID supersededId = insertInvite("superseded@beautica.test", salonId, "SALON_MASTER",
                Instant.now().minus(2, ChronoUnit.DAYS), false, "SUPERSEDED", hoursAgo(4));
        UUID cancelledId = insertInvite("cancelled@beautica.test", salonId, "SALON_MASTER",
                Instant.now().plus(7, ChronoUnit.DAYS), true, "CANCELLED", hoursAgo(5));

        String ownerToken = loginAndGetToken(emailOf(ownerId));

        // Act
        log.debug("Act: GET the invite history of a salon holding one row of each derived status");
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(HISTORY_URL, salonId), HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(ownerToken)), String.class);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<SalonInviteResponse> invites = parseInvites(response.getBody());

        assertThat(invites)
                .as("every invite the salon ever sent must be listed, not just the live ones")
                .hasSize(5);
        assertThat(invites)
                .extracting(SalonInviteResponse::inviteId, SalonInviteResponse::status,
                        SalonInviteResponse::role)
                .as("each status is derived from that row's own (revoked_reason, is_used, "
                        + "expires_at) triple — body: %s", response.getBody())
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(pendingId, "PENDING", "SALON_MASTER"),
                        org.assertj.core.groups.Tuple.tuple(acceptedId, "ACCEPTED", "SALON_ADMIN"),
                        org.assertj.core.groups.Tuple.tuple(lapsedId, "EXPIRED", "SALON_MASTER"),
                        org.assertj.core.groups.Tuple.tuple(supersededId, "EXPIRED", "SALON_MASTER"),
                        org.assertj.core.groups.Tuple.tuple(cancelledId, "CANCELLED", "SALON_MASTER"));
    }

    /**
     * Ordering has to be proven against real SQL, and only an out-of-insertion-order fixture can
     * prove it: seeding rows in the order they should come back makes {@code ORDER BY} redundant
     * and the assertion vacuous. Here row insertion order (2nd-oldest, newest, oldest, 2nd-newest)
     * deliberately matches neither the expected output nor the reverse of it, and no two rows
     * share a {@code created_at}.
     */
    @Test
    @DisplayName("GET /invites — rows come back strictly newest-first by createdAt, from a fixture "
            + "inserted deliberately OUT of that order")
    void should_returnNewestFirst_when_rowsWereInsertedOutOfOrder() throws Exception {
        UUID ownerId = insertUser("owner-order-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = insertSalon(ownerId, "Ordering Salon");

        // Inserted 3rd-newest, newest, oldest, 2nd-newest — no relationship to the expected order.
        UUID third = insertInvite("third@beautica.test", salonId, "SALON_MASTER",
                Instant.now().plus(7, ChronoUnit.DAYS), false, null, hoursAgo(30));
        UUID first = insertInvite("first@beautica.test", salonId, "SALON_MASTER",
                Instant.now().plus(7, ChronoUnit.DAYS), false, null, hoursAgo(1));
        UUID fourth = insertInvite("fourth@beautica.test", salonId, "SALON_MASTER",
                Instant.now().plus(7, ChronoUnit.DAYS), false, null, hoursAgo(200));
        UUID second = insertInvite("second@beautica.test", salonId, "SALON_MASTER",
                Instant.now().plus(7, ChronoUnit.DAYS), false, null, hoursAgo(10));

        String ownerToken = loginAndGetToken(emailOf(ownerId));

        log.debug("Act: GET the invite history of a salon whose rows were inserted out of created_at order");
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(HISTORY_URL, salonId), HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(ownerToken)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parseInvites(response.getBody()))
                .extracting(SalonInviteResponse::inviteId)
                .as("the client renders the list verbatim and does NOT re-sort, so the server's "
                        + "order IS the contract — body: %s", response.getBody())
                .containsExactly(first, second, third, fourth);
    }

    /**
     * {@code invite_tokens.token} stores a SHA-256 digest of a live credential. The DTO simply has
     * no such component, and {@link com.beautica.user.InviteToken#getToken()} is
     * {@code @JsonIgnore}d — this asserts the RENDERED BYTES, which is the only place a future
     * serialiser change (a {@code @JsonAnyGetter}, an entity leaking into the response) would show.
     */
    @Test
    @DisplayName("GET /invites — the rendered JSON contains no token field and no token material")
    void should_neverSerialiseTokenMaterial_when_listingHistory() throws Exception {
        UUID ownerId = insertUser("owner-notoken-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = insertSalon(ownerId, "No Token Leak Salon");
        UUID inviteId = insertInvite("leak-check@beautica.test", salonId, "SALON_MASTER",
                Instant.now().plus(7, ChronoUnit.DAYS), false, null, hoursAgo(1));
        String storedToken = jdbcTemplate.queryForObject(
                "SELECT token FROM invite_tokens WHERE id = ?", String.class, inviteId);
        String ownerToken = loginAndGetToken(emailOf(ownerId));

        log.debug("Act: GET the invite history and inspect the raw response bytes for token material");
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(HISTORY_URL, salonId), HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(ownerToken)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("no `token` key may appear anywhere in the payload")
                .doesNotContain("\"token\"")
                .as("nor the stored digest itself, under any key name")
                .doesNotContain(storedToken);
        // Positive control — the row IS in the response, so the assertions above are not passing
        // merely because the payload is empty.
        assertThat(parseInvites(response.getBody()))
                .extracting(SalonInviteResponse::inviteId)
                .containsExactly(inviteId);
    }

    @Test
    @DisplayName("GET /invites — 200 when SALON_ADMIN lists their own salon's history")
    void should_return200_when_adminListsOwnSalonHistory() throws Exception {
        UUID ownerId = insertUser("owner-admin-list-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = insertSalon(ownerId, "Admin Lists Invites Salon");
        UUID adminId = insertSalonAdminUser("admin-list-" + System.nanoTime() + "@beautica.test", salonId);
        UUID inviteId = insertInvite("invitee-admin@beautica.test", salonId, "SALON_ADMIN",
                Instant.now().plus(7, ChronoUnit.DAYS), false, null, hoursAgo(1));
        String adminToken = loginAndGetToken(emailOf(adminId));

        log.debug("Act: GET the invite history as the salon's own SALON_ADMIN");
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(HISTORY_URL, salonId), HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(adminToken)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parseInvites(response.getBody()))
                .extracting(SalonInviteResponse::inviteId, SalonInviteResponse::status)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(inviteId, "PENDING"));
    }

    @Test
    @DisplayName("GET /invites — 403 when SALON_ADMIN of a DIFFERENT salon reads this salon's "
            + "history (cross-salon IDOR, a genuinely authenticated other actor)")
    void should_return403_when_adminFromDifferentSalonListsHistory() throws Exception {
        UUID ownerAId = insertUser("owner-a-list-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonAId = insertSalon(ownerAId, "Salon A List");
        insertInvite("invitee-a@beautica.test", salonAId, "SALON_MASTER",
                Instant.now().plus(7, ChronoUnit.DAYS), false, null, hoursAgo(1));

        UUID ownerBId = insertUser("owner-b-list-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonBId = insertSalon(ownerBId, "Salon B List");
        UUID adminBId = insertSalonAdminUser("admin-b-list-" + System.nanoTime() + "@beautica.test", salonBId);
        String adminBToken = loginAndGetToken(emailOf(adminBId));

        log.debug("Act: Salon B's admin puts Salon A's id in the path and reads its invite history");
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(HISTORY_URL, salonAId), HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(adminBToken)), String.class);

        assertThat(response.getStatusCode())
                .as("cross-salon invite listing must be denied with 403 — recipient e-mail addresses "
                        + "are PII belonging to the other salon")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("GET /invites — 403 when a SALON_OWNER who does not own this salon reads its history")
    void should_return403_when_nonManagingOwnerListsHistory() throws Exception {
        UUID ownerAId = insertUser("owner-a-hist-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonAId = insertSalon(ownerAId, "Salon A Owner Isolation");
        insertInvite("invitee-a-owner@beautica.test", salonAId, "SALON_MASTER",
                Instant.now().plus(7, ChronoUnit.DAYS), false, null, hoursAgo(1));

        UUID ownerBId = insertUser("owner-b-hist-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        insertSalon(ownerBId, "Salon B Owner Isolation");
        String ownerBToken = loginAndGetToken(emailOf(ownerBId));

        log.debug("Act: an owner of a DIFFERENT salon reads Salon A's invite history");
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(HISTORY_URL, salonAId), HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(ownerBToken)), String.class);

        assertThat(response.getStatusCode())
                .as("holding the SALON_OWNER role is not access to an arbitrary salon's invites")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("GET /invites — 403 when a CLIENT attempts to read a salon's invite history")
    void should_return403_when_clientListsHistory() throws Exception {
        UUID ownerId = insertUser("owner-client-list-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = insertSalon(ownerId, "Client Denied Salon");
        UUID clientId = insertUser("client-list-" + System.nanoTime() + "@beautica.test", "CLIENT");
        String clientToken = loginAndGetToken(emailOf(clientId));

        ResponseEntity<String> response = restTemplate.exchange(
                String.format(HISTORY_URL, salonId), HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(clientToken)), String.class);

        assertThat(response.getStatusCode())
                .as("CLIENT must be denied access to the invite history")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("GET /invites — 401 when no bearer token is supplied")
    void should_return401_when_noToken() {
        UUID ownerId = insertUser("owner-anon-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = insertSalon(ownerId, "Anonymous Denied Salon");

        ResponseEntity<String> response = restTemplate.exchange(
                String.format(HISTORY_URL, salonId), HttpMethod.GET, HttpEntity.EMPTY, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("GET /invites — 200 with an empty list when the salon has never invited anyone")
    void should_returnEmptyList_when_salonHasNoInvites() throws Exception {
        UUID ownerId = insertUser("owner-empty-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = insertSalon(ownerId, "Never Invited Salon");
        String ownerToken = loginAndGetToken(emailOf(ownerId));

        ResponseEntity<String> response = restTemplate.exchange(
                String.format(HISTORY_URL, salonId), HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(ownerToken)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parseInvites(response.getBody())).isEmpty();
    }

    /**
     * <strong>REGRESSION GUARD — fails on the pre-change code for TWO independent reasons.</strong>
     *
     * <ol>
     *   <li>The old endpoint's query was
     *       {@code findBySalonIdAndIsUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc}, and cancel
     *       set {@code is_used = true}. The cancelled row therefore vanished from the listing
     *       entirely, so the {@code containsExactly(inviteId)} assertion below found an EMPTY list.
     *   <li>Even had the row been returned, {@code PendingInviteResponse} carried no {@code status}
     *       at all, and there was no column able to distinguish a cancellation from an acceptance —
     *       both wrote the same single {@code is_used} flag. {@code "CANCELLED"} was not an
     *       expressible answer.
     * </ol>
     *
     * <p>It goes through the REAL {@code DELETE} endpoint rather than seeding a revoked row, so it
     * also pins {@code SalonService#cancelInvite} → {@code InviteToken#markCancelled} to the
     * status the history endpoint derives — the two halves cannot drift apart silently.
     */
    @Test
    @DisplayName("GET /invites — a cancelled invite STAYS in the history, labelled CANCELLED "
            + "(regression guard: the pre-change endpoint dropped it and could not have labelled it)")
    void should_keepCancelledInviteInHistoryAsCancelled_when_ownerCancelsIt() throws Exception {
        // Arrange
        UUID ownerId = insertUser("owner-cancel-hist-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = insertSalon(ownerId, "Cancel Stays In History Salon");
        UUID inviteId = insertInvite("cancel-history@beautica.test", salonId, "SALON_MASTER",
                Instant.now().plus(7, ChronoUnit.DAYS), false, null, hoursAgo(1));
        String ownerToken = loginAndGetToken(emailOf(ownerId));

        // Act 1 — cancel through the real endpoint
        log.debug("Act 1: DELETE the only invite, then re-read the history to prove it survived");
        ResponseEntity<Void> cancelResponse = restTemplate.exchange(
                String.format(CANCEL_URL, salonId, inviteId), HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(ownerToken)), Void.class);
        assertThat(cancelResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Act 2 — the history read
        ResponseEntity<String> historyResponse = restTemplate.exchange(
                String.format(HISTORY_URL, salonId), HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(ownerToken)), String.class);

        // Assert
        assertThat(historyResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parseInvites(historyResponse.getBody()))
                .as("cancelling must not erase the invite from the owner's history — body: %s",
                        historyResponse.getBody())
                .extracting(SalonInviteResponse::inviteId, SalonInviteResponse::status)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(inviteId, "CANCELLED"));

        // And the DB actually carries the distinguishing marker, not merely is_used.
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT revoked_reason FROM invite_tokens WHERE id = ?", String.class, inviteId))
                .as("is_used alone cannot tell CANCELLED from ACCEPTED — revoked_reason is the bit "
                        + "that carries the difference")
                .isEqualTo("CANCELLED");
    }

    // ── DELETE /invites/{inviteId} ────────────────────────────────────────────

    @Test
    @DisplayName("204 and marks used when SALON_OWNER cancels a pending invite; second cancel 404s")
    void should_return204AndMarkUsed_when_ownerCancelsPendingInvite_and_404sOnSecondCancel() throws Exception {
        // Arrange
        UUID ownerId = insertUser("owner-cancel-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = insertSalon(ownerId, "Cancel Invite Salon");
        UUID inviteId = insertInvite("cancel-target@beautica.test", salonId, "SALON_MASTER",
                Instant.now().plus(7, ChronoUnit.DAYS), false, null, hoursAgo(1));
        String ownerToken = loginAndGetToken(emailOf(ownerId));

        // Act — first cancel
        log.debug("Act: DELETE a pending invite, then DELETE the same id again to prove it 404s");
        ResponseEntity<Void> firstResponse = restTemplate.exchange(
                String.format(CANCEL_URL, salonId, inviteId), HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(ownerToken)),
                Void.class);

        // Assert — first cancel succeeds and flips is_used
        assertThat(firstResponse.getStatusCode())
                .as("cancelling a pending invite must return 204")
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(readIsUsed(inviteId))
                .as("cancelled invite must keep is_used = true, never be deleted — every downstream "
                        + "already-consumed guard reads that flag")
                .isTrue();

        // Act — second cancel of the same (now-cancelled) invite
        ResponseEntity<String> secondResponse = restTemplate.exchange(
                String.format(CANCEL_URL, salonId, inviteId), HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(ownerToken)),
                String.class);

        // Assert — second cancel 404s
        assertThat(secondResponse.getStatusCode())
                .as("cancelling an already-cancelled invite must return 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("204 when SALON_ADMIN cancels a pending invite for their own salon")
    void should_return204_when_adminCancelsOwnSalonInvite() throws Exception {
        UUID ownerId = insertUser("owner-admin-cancel-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = insertSalon(ownerId, "Admin Cancel Salon");
        UUID adminId = insertSalonAdminUser("admin-cancel-" + System.nanoTime() + "@beautica.test", salonId);
        UUID inviteId = insertInvite("admin-cancel-target@beautica.test", salonId,
                "SALON_MASTER", Instant.now().plus(7, ChronoUnit.DAYS), false, null, hoursAgo(1));
        String adminToken = loginAndGetToken(emailOf(adminId));

        ResponseEntity<Void> response = restTemplate.exchange(
                String.format(CANCEL_URL, salonId, inviteId), HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(adminToken)),
                Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(readIsUsed(inviteId)).isTrue();
        assertThat(readRevokedReason(inviteId)).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("403 when SALON_ADMIN from a DIFFERENT salon cancels an invite (cross-salon IDOR, @PreAuthorize gate)")
    void should_return403_when_adminFromDifferentSalonCancelsInvite() throws Exception {
        // Arrange — salon A's invite, salon B's admin
        UUID ownerAId = insertUser("owner-a-cancel-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonAId = insertSalon(ownerAId, "Salon A Cancel");
        UUID inviteAId = insertInvite("invite-a-cancel@beautica.test", salonAId,
                "SALON_MASTER", Instant.now().plus(7, ChronoUnit.DAYS), false, null, hoursAgo(1));

        UUID ownerBId = insertUser("owner-b-cancel-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonBId = insertSalon(ownerBId, "Salon B Cancel");
        UUID adminBId = insertSalonAdminUser("admin-b-cancel-" + System.nanoTime() + "@beautica.test", salonBId);
        String adminBToken = loginAndGetToken(emailOf(adminBId));

        // Act — Salon B's admin targets Salon A's invite via Salon A's id in the path
        log.debug("Act: Salon B's admin puts Salon A's id in the path and cancels Salon A's invite");
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(CANCEL_URL, salonAId, inviteAId), HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(adminBToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("cross-salon invite cancellation must be denied with 403 at the @PreAuthorize gate")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(readIsUsed(inviteAId))
                .as("Salon A's invite must be untouched")
                .isFalse();
        assertThat(readRevokedReason(inviteAId)).isNull();
    }

    @Test
    @DisplayName("404 when cancelling an invite that belongs to a DIFFERENT salon than the path salonId "
            + "(defense-in-depth service-layer check, distinct from the @PreAuthorize gate)")
    void should_return404_when_cancellingInviteFromDifferentSalonViaOwnAuthorizedSalon() throws Exception {
        // Arrange — the actor legitimately manages Salon B (passes @PreAuthorize), but the
        // inviteId in the path belongs to Salon A. The @PreAuthorize gate alone cannot catch
        // this — it only checks the actor's access to the PATH salonId, not whether the invite
        // itself belongs there. SalonService.cancelInvite's defense-in-depth check must 404.
        UUID ownerAId = insertUser("owner-a-mismatch-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonAId = insertSalon(ownerAId, "Salon A Mismatch");
        UUID inviteAId = insertInvite("invite-a-mismatch@beautica.test", salonAId,
                "SALON_MASTER", Instant.now().plus(7, ChronoUnit.DAYS), false, null, hoursAgo(1));

        UUID ownerBId = insertUser("owner-b-mismatch-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonBId = insertSalon(ownerBId, "Salon B Mismatch");
        String ownerBToken = loginAndGetToken(emailOf(ownerBId));

        // Act — Owner B supplies THEIR OWN salonId (authorized) but Salon A's inviteId
        log.debug("Act: authorised owner of Salon B cancels using Salon A's inviteId in the path");
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(CANCEL_URL, salonBId, inviteAId), HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(ownerBToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("an invite belonging to a different salon than the path salonId must 404, "
                        + "never cancel across salons")
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(readIsUsed(inviteAId))
                .as("Salon A's invite must be untouched")
                .isFalse();
    }

    @Test
    @DisplayName("403 when a CLIENT attempts to cancel a pending invite")
    void should_return403_when_clientCancelsPendingInvite() throws Exception {
        // Arrange
        UUID ownerId = insertUser("owner-client-cancel-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = insertSalon(ownerId, "Client Cancel Denied Salon");
        UUID inviteId = insertInvite("cancel-denied@beautica.test", salonId,
                "SALON_MASTER", Instant.now().plus(7, ChronoUnit.DAYS), false, null, hoursAgo(1));
        UUID clientId = insertUser("client-cancel-" + System.nanoTime() + "@beautica.test", "CLIENT");
        String clientToken = loginAndGetToken(emailOf(clientId));

        // Act
        log.debug("Act: an authenticated CLIENT tries to cancel a salon's pending invite");
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(CANCEL_URL, salonId, inviteId), HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(clientToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("CLIENT must be denied access to the invite-cancellation endpoint")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(readIsUsed(inviteId))
                .as("a denied CLIENT must never be able to mutate the invite")
                .isFalse();
    }

    @Test
    @DisplayName("a cancelled invite can no longer be accepted via POST /auth/invite/accept "
            + "(end-to-end, not merely implied by the used-token unit/integration coverage)")
    void should_rejectAcceptOfInvite_when_previouslyCancelledByOwner() throws Exception {
        // Arrange — a genuine pending invite, cancelled by the owner through the real endpoint
        UUID ownerId = insertUser("owner-cancel-then-accept-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = insertSalon(ownerId, "Cancel Then Accept Salon");
        String rawToken = "raw-cancel-then-accept-" + UUID.randomUUID();
        UUID inviteId = insertInviteTokenWithRawToken(
                "cancel-then-accept-" + System.nanoTime() + "@beautica.test", salonId, "SALON_MASTER",
                Instant.now().plus(7, ChronoUnit.DAYS), rawToken);
        String ownerToken = loginAndGetToken(emailOf(ownerId));

        log.debug("Act 1: the owner cancels the invite before the invitee ever opens their link");
        ResponseEntity<Void> cancelResponse = restTemplate.exchange(
                String.format(CANCEL_URL, salonId, inviteId), HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(ownerToken)),
                Void.class);
        assertThat(cancelResponse.getStatusCode())
                .as("the owner's cancellation must succeed before the accept attempt below")
                .isEqualTo(HttpStatus.NO_CONTENT);

        // Act 2 — the invitee, who never saw the cancellation, tries to accept the now-dead link
        var acceptRequest = new com.beautica.auth.dto.InviteAcceptRequest(
                rawToken, "Str0ngP@ss1!", "Jane", "Doe", "+380501234567");
        log.debug("Act 2: the invitee posts the cancelled invite's raw token to /auth/invite/accept");
        ResponseEntity<String> acceptResponse = restTemplate.postForEntity(
                "/api/v1/auth/invite/accept", acceptRequest, String.class);

        // Assert — the cancellation is a real security boundary, not merely a hidden list entry
        assertThat(acceptResponse.getStatusCode())
                .as("accepting a cancelled invite must be rejected, never provision an account")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        var body = objectMapper.readValue(
                acceptResponse.getBody(),
                new TypeReference<ApiResponse<com.beautica.auth.dto.InviteErrorResponse>>() {});
        assertThat(body.success()).isFalse();
        assertThat(body.data().code())
                .as("phase 285: a cancelled invite must report INVITE_REVOKED, not INVITE_USED")
                .isEqualTo(com.beautica.common.exception.InviteTokenException.Code.INVITE_REVOKED.name());
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM users WHERE email = (SELECT email FROM invite_tokens WHERE id = ?)",
                        Integer.class, inviteId))
                .as("no account must ever be provisioned from a cancelled invite")
                .isZero();
    }

    /**
     * S5, pinned at the WIRE, not just in the service. The truncation signal is only useful if it
     * actually reaches the client, so this asserts the JSON keys directly: a refactor that went
     * back to serialising a bare array — or that renamed either key — would break every consumer
     * silently, and no service-level test can see it.
     */
    @Test
    @DisplayName("GET /invites — the payload is an object carrying `invites` and `truncated`, and "
            + "truncated is false for a salon under the cap")
    void should_carryTruncationFlagOnTheWire_when_listingHistory() throws Exception {
        UUID ownerId = insertUser("owner-trunc-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = insertSalon(ownerId, "Truncation Signal Salon");
        UUID inviteId = insertInvite("trunc-check@beautica.test", salonId, "SALON_MASTER",
                Instant.now().plus(7, ChronoUnit.DAYS), false, null, hoursAgo(1));
        String ownerToken = loginAndGetToken(emailOf(ownerId));

        log.debug("Act: GET the invite history and inspect the raw JSON for the truncation signal");
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(HISTORY_URL, salonId), HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(ownerToken)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var tree = objectMapper.readTree(response.getBody()).get("data");
        assertThat(tree.get("invites").isArray())
                .as("the rows must live under `data.invites` — body: %s", response.getBody())
                .isTrue();
        assertThat(tree.get("truncated"))
                .as("`data.truncated` must be present on every response, not only truncated ones; "
                        + "an absent key is indistinguishable from false to a client")
                .isNotNull();
        assertThat(tree.get("truncated").asBoolean())
                .as("one invite is far below the 200-row cap — nothing was dropped")
                .isFalse();
        assertThat(parseHistory(response.getBody()).invites())
                .extracting(SalonInviteResponse::inviteId)
                .containsExactly(inviteId);
    }

    /**
     * S1. A SUPERSEDED row keeps {@code is_used = false}, so it slipped past the original
     * {@code isUsed()}-only cancel guard — and {@code markCancelled} OVERWRITES
     * {@code revoked_at}/{@code revoked_reason}. An owner could rewrite the recorded outcome of an
     * invite nobody ever cancelled, turning the audit trail into a lie. Asserted against the DB,
     * because a 404 alone would not prove the row survived unmodified.
     */
    @Test
    @DisplayName("404 when cancelling a SUPERSEDED invite, and its recorded outcome is not rewritten")
    void should_return404_when_cancellingSupersededInvite() throws Exception {
        UUID ownerId = insertUser("owner-cancel-superseded-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = insertSalon(ownerId, "Cancel Superseded Salon");
        UUID inviteId = insertInvite("superseded-target@beautica.test", salonId, "SALON_MASTER",
                Instant.now().minus(1, ChronoUnit.DAYS), false, "SUPERSEDED", hoursAgo(48));
        String ownerToken = loginAndGetToken(emailOf(ownerId));

        log.debug("Act: the owner DELETEs an invite that was already retired by a re-invite");
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(CANCEL_URL, salonId, inviteId), HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(ownerToken)), String.class);

        assertThat(response.getStatusCode())
                .as("only a PENDING invite is cancellable — a superseded one must 404, exactly as "
                        + "the endpoint's OpenAPI contract states")
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(readRevokedReason(inviteId))
                .as("SUPERSEDED must survive; rewriting it to CANCELLED would attribute an outcome "
                        + "to the owner that never happened")
                .isEqualTo("SUPERSEDED");
        assertThat(readIsUsed(inviteId))
                .as("a superseded invite was never consumed and must not become so")
                .isFalse();
    }

    /**
     * S1, the plain-expiry half. {@code revoked_at} null and {@code expires_at} in the past — the
     * shape that carried NO marker at all, so nothing in the old guard could reject it. Cancelling
     * it changes nothing about reality but stamps it CANCELLED, hiding that it lapsed unanswered.
     */
    @Test
    @DisplayName("404 when cancelling an invite that has simply expired, and it is not relabelled "
            + "CANCELLED")
    void should_return404_when_cancellingExpiredInvite() throws Exception {
        UUID ownerId = insertUser("owner-cancel-expired-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = insertSalon(ownerId, "Cancel Expired Salon");
        UUID inviteId = insertInvite("expired-target@beautica.test", salonId, "SALON_MASTER",
                Instant.now().minus(1, ChronoUnit.HOURS), false, null, hoursAgo(72));
        String ownerToken = loginAndGetToken(emailOf(ownerId));

        log.debug("Act: the owner DELETEs an invite whose expires_at is already in the past");
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(CANCEL_URL, salonId, inviteId), HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(ownerToken)), String.class);

        assertThat(response.getStatusCode())
                .as("a lapsed invite is not PENDING, so it must 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(readRevokedReason(inviteId))
                .as("nobody cancelled this invite — it ran out of time; recording CANCELLED "
                        + "misattributes the outcome")
                .isNull();
        assertThat(readIsUsed(inviteId)).isFalse();
    }

    /**
     * The status the history reports for the row the cancel just refused stays EXPIRED. This is
     * the pairing that makes the two S1 tests above about the AUDIT TRAIL rather than about an
     * HTTP status code: the endpoint's whole job is telling the owner what happened, and a refused
     * cancel must leave that answer unchanged.
     */
    @Test
    @DisplayName("a refused cancel leaves the history's reported status untouched")
    void should_keepHistoryStatusUnchanged_when_cancelIsRefused() throws Exception {
        UUID ownerId = insertUser("owner-refused-cancel-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = insertSalon(ownerId, "Refused Cancel Salon");
        UUID supersededId = insertInvite("superseded-hist@beautica.test", salonId, "SALON_MASTER",
                Instant.now().minus(1, ChronoUnit.DAYS), false, "SUPERSEDED", hoursAgo(48));
        String ownerToken = loginAndGetToken(emailOf(ownerId));

        restTemplate.exchange(String.format(CANCEL_URL, salonId, supersededId), HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(ownerToken)), String.class);

        ResponseEntity<String> history = restTemplate.exchange(
                String.format(HISTORY_URL, salonId), HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(ownerToken)), String.class);

        assertThat(parseInvites(history.getBody()))
                .extracting(SalonInviteResponse::inviteId, SalonInviteResponse::status)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(supersededId, "EXPIRED"));
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private List<SalonInviteResponse> parseInvites(String body) throws Exception {
        return parseHistory(body).invites();
    }

    /**
     * The payload is a {@link SalonInviteHistoryResponse} object, not a bare array: the list alone
     * could not say whether it had been truncated at the 200-row cap. See that record's Javadoc.
     */
    private SalonInviteHistoryResponse parseHistory(String body) throws Exception {
        var parsed = objectMapper.readValue(
                body, new TypeReference<ApiResponse<SalonInviteHistoryResponse>>() {});
        assertThat(parsed.success()).as("envelope must report success — body: %s", body).isTrue();
        return parsed.data();
    }

    private static Instant hoursAgo(long hours) {
        return Instant.now().minus(hours, ChronoUnit.HOURS);
    }

    private UUID insertUser(String email, String role) {
        String hash = passwordEncoder.encode(TEST_PASSWORD);
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) VALUES (?, ?, ?, ?, true, true)",
                id, email, hash, role);
        return id;
    }

    private UUID insertSalonAdminUser(String email, UUID salonId) {
        String hash = passwordEncoder.encode(TEST_PASSWORD);
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, is_active, email_verified) VALUES (?, ?, ?, 'SALON_ADMIN', ?, true, true)",
                id, email, hash, salonId);
        return id;
    }

    private UUID insertSalon(UUID ownerId, String name) {
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at, city_id) VALUES (?, ?, ?, true, NOW(), NOW(), ?)",
                salonId, ownerId, name, testCityId());
        return salonId;
    }

    /**
     * Seeds one {@code invite_tokens} row with full control over every column the status ladder
     * reads plus {@code created_at} (the sort key). {@code revokedReason} of {@code null} leaves
     * {@code revoked_at} null too, satisfying V153's {@code ck_invite_tokens_revoked_pair} CHECK
     * — pass a reason and both columns are written together, exactly as the entity does.
     */
    private UUID insertInvite(String email, UUID salonId, String role, Instant expiresAt,
            boolean isUsed, String revokedReason, Instant createdAt) {
        UUID id = UUID.randomUUID();
        // Namespaced per row so parallel classes sharing the container cannot collide on the
        // UNIQUE token column, and so each row's stored value is distinctive enough for the
        // no-token-leak assertion to be meaningful.
        String token = "test-invite-token-" + id;
        jdbcTemplate.update(
                "INSERT INTO invite_tokens "
                        + "(id, token, email, salon_id, role, expires_at, is_used, revoked_at, revoked_reason, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())",
                id, token, email, salonId, role, java.sql.Timestamp.from(expiresAt), isUsed,
                revokedReason == null ? null : java.sql.Timestamp.from(createdAt), revokedReason,
                java.sql.Timestamp.from(createdAt));
        return id;
    }

    /**
     * Like {@link #insertInvite}, but stores the SHA-256 hex digest of {@code rawToken} —
     * matching {@code SecureTokenGenerator#hash} — so the row is genuinely acceptable via
     * {@code POST /auth/invite/accept} (which hashes the caller-supplied raw token before looking
     * it up). {@link #insertInvite}'s literal token value is never accepted by that endpoint;
     * this helper exists specifically for tests that exercise the accept flow end-to-end.
     */
    private UUID insertInviteTokenWithRawToken(String email, UUID salonId, String role, Instant expiresAt,
            String rawToken) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO invite_tokens (id, token, email, salon_id, role, expires_at, is_used, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, false, NOW(), NOW())",
                id, sha256Hex(rawToken), email, salonId, role, java.sql.Timestamp.from(expiresAt));
        return id;
    }

    private String sha256Hex(String input) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String emailOf(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }

    private Boolean readIsUsed(UUID inviteId) {
        return jdbcTemplate.queryForObject("SELECT is_used FROM invite_tokens WHERE id = ?", Boolean.class, inviteId);
    }

    private String readRevokedReason(UUID inviteId) {
        return jdbcTemplate.queryForObject(
                "SELECT revoked_reason FROM invite_tokens WHERE id = ?", String.class, inviteId);
    }

    private String loginAndGetToken(String email) throws Exception {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        return body.data().accessToken();
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
