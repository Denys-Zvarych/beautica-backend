package com.beautica.user;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.BookingTestFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the {@code chk_masters_detachment_coherent} CHECK constraint (V157, phase 294) against a
 * real Postgres, on the STAFF self-delete path — the sibling of {@link ClientDetachCoherenceIT}.
 *
 * <p>Two things a Mockito unit test cannot prove: (1) Postgres genuinely REJECTS a half-detached
 * {@code masters} row — {@code user_id = NULL} without the name snapshot and the {@code
 * detached_at} stamp landing in the SAME statement — which is exactly why {@code
 * StaffAccountDisposalService#dispose} must call {@code masterRepository.flush()} BEFORE {@code
 * userRepository.deleteAllByIdInBatch} (the FK's bare {@code ON DELETE SET NULL} firing on an
 * un-flushed detach UPDATE would leave the row satisfying NEITHER CHECK arm); and (2) a real
 * self-delete through the full HTTP → service → repository stack genuinely produces a coherent row.
 * If the promoted {@code flush()} call regressed, this class's last test — not just the mocked
 * ordering pin in {@code StaffAccountDisposalServiceTest} — is what turns a live 500 in production
 * into a caught failure here.
 */
@DisplayName("V157 chk_masters_detachment_coherent — Phase 301 staff-detach coherence")
class StaffDetachCoherenceIT extends AbstractIntegrationTest {

    private static final OffsetDateTime PAST = OffsetDateTime.now().minusDays(2);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private BookingTestFixtures fixtures;
    private ClientSelfDeleteTestFixtures csd;

    @BeforeEach
    void setUp() {
        fixtures = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
        csd = new ClientSelfDeleteTestFixtures(jdbcTemplate, passwordEncoder);
    }

    @Test
    @DisplayName("chk_masters_detachment_coherent REJECTS a half-detached row — user_id nulled with "
            + "NO name snapshot and NO detached_at stamp")
    void should_rejectHalfDetachedMaster_underCheck() {
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE masters SET user_id = NULL WHERE id = ?", salon.masterId()))
                .as("a half-detached row (no snapshot, no stamp) must be UNWRITABLE")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("chk_masters_detachment_coherent ACCEPTS a fully-formed detached row written "
            + "directly — proves the DETACHED arm itself, independent of the service code")
    void should_acceptFullyFormedDetachedMaster_underCheck() {
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();

        jdbcTemplate.update(
                "UPDATE masters SET user_id = NULL, detached_first_name = 'Тест', "
                        + "detached_last_name = 'Майстер', detached_at = NOW(), is_active = false "
                        + "WHERE id = ?",
                salon.masterId());

        assertThat(csd.masterUserId(salon.masterId())).isNull();
        assertThat(csd.masterIsActive(salon.masterId())).isFalse();
    }

    @Test
    @DisplayName("a real SALON_MASTER self-delete with booking history produces a COHERENT "
            + "detached row end-to-end (204, not a 500 CHECK violation) — the flush-before-delete "
            + "ordering under a real transaction, not a mocked one")
    void should_produceCoherentDetachedRow_when_masterWithHistorySelfDeletes() throws Exception {
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        UUID clientId = csd.createClient();
        csd.insertBooking(clientId, salon, "COMPLETED", PAST);
        String token = fixtures.tokenFor(emailOf(salon.masterUserId()));

        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), Void.class);

        assertThat(response.getStatusCode())
                .as("a 500 here means the flush-before-delete ordering broke and the CHECK fired "
                        + "against a half-written row")
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(csd.masterExists(salon.masterId())).isTrue();
        assertThat(csd.masterIsActive(salon.masterId())).isFalse();
        assertThat(csd.masterUserId(salon.masterId())).isNull();
        assertThat(csd.masterDetachedFirstName(salon.masterId())).isNotBlank();
        assertThat(csd.masterDetachedAt(salon.masterId())).isNotNull();
    }

    private String emailOf(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }
}
