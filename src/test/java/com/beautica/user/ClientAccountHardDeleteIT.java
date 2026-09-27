package com.beautica.user;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.BookingTestFixtures;
import com.beautica.booking.dto.CancelBookingRequest;
import com.beautica.booking.enums.CancellationReason;
import com.beautica.booking.service.BookingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * Real-DB happy-path coverage for {@code DELETE /api/v1/users/me} (Phase 300) — the CLIENT
 * self-deletion hard-delete cascade — mirroring {@code SalonStaffHardDeleteIT}'s structure and
 * raw-SQL fixture conventions for the analogous staff hard-delete track.
 *
 * <p>Everything at the Mockito level is already covered by {@link ClientAccountDeletionServiceTest}
 * (9 unit tests) — this class exists for what a mock cannot prove: that Postgres actually accepts
 * the writes under the real V162/V163 schema, that the CASCADE FKs actually fire, and that the
 * caller's OWN access token — obtained through a real login — really stops authenticating
 * afterwards.
 */
@DisplayName("DELETE /api/v1/users/me — Phase 300 CLIENT self-deletion hard-delete cascade")
class ClientAccountHardDeleteIT extends AbstractIntegrationTest {

    private static final OffsetDateTime FUTURE = OffsetDateTime.now().plusDays(7);
    private static final OffsetDateTime PAST = OffsetDateTime.now().minusDays(2);
    private static final String SELF_DELETE_NOTE = "Клієнт видалив акаунт.";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @SpyBean
    private BookingService bookingService;

    private BookingTestFixtures fixtures;
    private ClientSelfDeleteTestFixtures csd;

    @BeforeEach
    void setUp() {
        fixtures = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
        csd = new ClientSelfDeleteTestFixtures(jdbcTemplate, passwordEncoder);
    }

    @Test
    @DisplayName("the users row is physically gone, and every CASCADE-FK'd session/favorite row "
            + "goes with it")
    void should_hardDeleteUsersRowAndCascadeSessionsAndFavorites_when_clientSelfDeletes() throws Exception {
        UUID clientId = csd.createClient();
        String email = emailOf(clientId);
        String token = fixtures.tokenFor(email); // real login — seeds ONE refresh_tokens row
        csd.insertDeviceToken(clientId);
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        csd.insertFavorite(clientId, "SALON", salon.salonId());
        // Fixture check — every count is non-zero BEFORE the delete, so the zeros below prove a
        // cascade rather than a fixture that never inserted anything.
        assertThat(csd.count("SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ?", clientId))
                .isEqualTo(1);
        assertThat(csd.count("SELECT COUNT(*) FROM device_tokens WHERE user_id = ?", clientId))
                .isEqualTo(1);
        assertThat(csd.count("SELECT COUNT(*) FROM favorites WHERE client_id = ?", clientId))
                .isEqualTo(1);

        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(csd.userExists(clientId)).isFalse();
        assertThat(csd.count("SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ?", clientId))
                .as("refresh_tokens.user_id ON DELETE CASCADE")
                .isZero();
        assertThat(csd.count("SELECT COUNT(*) FROM device_tokens WHERE user_id = ?", clientId))
                .as("device_tokens.user_id ON DELETE CASCADE")
                .isZero();
        assertThat(csd.count("SELECT COUNT(*) FROM favorites WHERE client_id = ?", clientId))
                .as("favorites.client_id ON DELETE CASCADE")
                .isZero();
    }

    @Test
    @DisplayName("a future CONFIRMED booking is cancelled through the ordinary client-cancel path "
            + "(CLIENT_CANCELLED + the fixed system note) and then physically deleted")
    void should_cancelThenDeleteFutureBooking_withClientCancelledReasonAndSystemNote() throws Exception {
        UUID clientId = csd.createClient();
        String token = fixtures.tokenFor(emailOf(clientId));
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        UUID futureBookingId = csd.insertBooking(clientId, salon, "CONFIRMED", FUTURE);

        restTemplate.exchange("/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), Void.class);

        ArgumentCaptor<CancelBookingRequest> captor = ArgumentCaptor.forClass(CancelBookingRequest.class);
        verify(bookingService).cancelBooking(eq(clientId), eq(futureBookingId), captor.capture());
        assertThat(captor.getValue().cancellationReason())
                .as("D4 — the ORDINARY client-cancel reason, never a provider-side decline reason")
                .isEqualTo(CancellationReason.CLIENT_CANCELLED);
        assertThat(captor.getValue().comment())
                .as("the fixed Ukrainian system note, verbatim")
                .isEqualTo(SELF_DELETE_NOTE);
        assertThat(csd.bookingExists(futureBookingId))
                .as("D4 — cancelled THEN physically deleted, never left as a CANCELLED row")
                .isFalse();
    }

    @Test
    @DisplayName("a past COMPLETED booking survives DETACHED: client_id NULL, the sentinel "
            + "guest_name (never the real name), guest_surname NULL, client_detached_at stamped")
    void should_detachPastBooking_withSentinelGuestNameAndDetachedAtStamped() throws Exception {
        UUID clientId = csd.createClient();
        String token = fixtures.tokenFor(emailOf(clientId));
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        UUID pastBookingId = csd.insertBooking(clientId, salon, "COMPLETED", PAST);
        // Fixture check — the row genuinely names this client and carries no guest fields yet.
        assertThat(clientIdOf(pastBookingId)).isEqualTo(clientId);
        assertThat(guestNameOf(pastBookingId)).isNull();

        restTemplate.exchange("/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), Void.class);

        assertThat(csd.bookingExists(pastBookingId))
                .as("D4 — a past booking is DETACHED, never deleted")
                .isTrue();
        assertThat(clientIdOf(pastBookingId)).isNull();
        assertThat(guestNameOf(pastBookingId))
                .as("the sentinel, and NEVER the client's real first name (\"Оксана\")")
                .isEqualTo("Видалений клієнт")
                .isNotEqualTo("Оксана");
        assertThat(guestSurnameOf(pastBookingId)).isNull();
        assertThat(clientDetachedAtOf(pastBookingId)).isNotNull();
    }

    @Test
    @DisplayName("a second DELETE /users/me with the SAME still-valid pre-delete access token is "
            + "rejected 401 — the account is gone and the token is denylisted")
    void should_return401_when_sameAccessTokenReusedAfterSelfDelete() throws Exception {
        UUID clientId = csd.createClient();
        String token = fixtures.tokenFor(emailOf(clientId));
        HttpEntity<Void> authed = new HttpEntity<>(fixtures.bearerHeaders(token));

        ResponseEntity<Void> first = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE, authed, Void.class);
        assertThat(first.getStatusCode())
                .as("control — the very same token must work for the first call")
                .isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> second = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE, authed, String.class);

        assertThat(second.getStatusCode())
                .as("the account row is gone AND the jti is denylisted — either alone would already "
                        + "produce this, together they make it doubly certain")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private String emailOf(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }

    private UUID clientIdOf(UUID bookingId) {
        return jdbcTemplate.queryForObject("SELECT client_id FROM bookings WHERE id = ?", UUID.class, bookingId);
    }

    private String guestNameOf(UUID bookingId) {
        return jdbcTemplate.queryForObject("SELECT guest_name FROM bookings WHERE id = ?", String.class, bookingId);
    }

    private String guestSurnameOf(UUID bookingId) {
        return jdbcTemplate.queryForObject(
                "SELECT guest_surname FROM bookings WHERE id = ?", String.class, bookingId);
    }

    private Timestamp clientDetachedAtOf(UUID bookingId) {
        return jdbcTemplate.queryForObject(
                "SELECT client_detached_at FROM bookings WHERE id = ?", Timestamp.class, bookingId);
    }
}
