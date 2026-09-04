package com.beautica.salon;

import com.beautica.AbstractIntegrationTest;
import com.beautica.media.service.MediaService;
import com.beautica.salon.service.SalonService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;

/**
 * Phase 268 — pins {@code SalonService#purgeSalonMediaAfterCommit}'s own {@code try/catch}
 * (D4/D8: "never lets a failure surface as a 500 on an already-committed deletion", see that
 * method's Javadoc, {@code SalonService.java:445-449}).
 *
 * <p>Why this needs its own class rather than living in {@link SalonDeactivationCascadeIT} or a
 * plain Mockito unit test:
 * <ul>
 *   <li>{@code purgeSalonMediaAfterCommit} registers a {@code TransactionSynchronization} only
 *       when {@code TransactionSynchronizationManager.isSynchronizationActive()} is {@code true}
 *       — which is never the case under {@code @ExtendWith(MockitoExtension.class)}. A plain
 *       unit test (e.g. {@code SalonServiceTest}) therefore cannot exercise this callback, let
 *       alone its {@code try/catch}, at all — it needs a real {@code @Transactional} commit.</li>
 *   <li>{@link SalonDeactivationCascadeIT} needs the REAL {@code MediaService} — several of its
 *       own phase-268 cases assert the actual DB rows/columns the real sweep leaves behind. This
 *       class {@code @MockBean}s {@code MediaService} instead, which forces a SEPARATE Spring
 *       context (a different {@code @MockBean} set is a different cache key) so that override
 *       never leaks into the sibling class.</li>
 *   <li>{@code R2StorageService} is disabled in the {@code test} profile (see
 *       {@code application-test.yml}), so its own {@code deleteFile} is already a no-op that
 *       never throws — mocking it would not reach this outer guard at all. {@code MediaService}
 *       itself must throw to prove the OUTER catch (as opposed to {@code sweepBlobs}'s own
 *       per-row catch, already pinned by {@code MediaServiceTest}) is the one doing the work.</li>
 * </ul>
 */
@DisplayName("SalonService.purgeSalonMediaAfterCommit — Phase 268 item 5: a media-purge failure "
        + "must never surface out of deactivateSalon")
class SalonMediaPurgeFailureIT extends AbstractIntegrationTest {

    private static final String TEST_PASSWORD = "Str0ngP@ss1!";

    @Autowired
    private SalonService salonService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockBean
    private MediaService mediaService;

    @BeforeEach
    void pushOwnerAuthentication() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "test@example.com", null,
                        List.of(new SimpleGrantedAuthority("ROLE_SALON_OWNER"))));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("deactivateSalon returns normally, and the salon is left committed inactive, "
            + "even though the after-commit media purge throws")
    void should_completeDeactivation_when_mediaPurgeThrowsAfterCommit() {
        UUID ownerId = createOwner();
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at, city_id) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW(), ?)",
                salonId, ownerId, "Salon-" + salonId, testCityId());

        doThrow(new RuntimeException("simulated media-purge failure"))
                .when(mediaService).deleteBySalon(any(), any(), any(), anyList());

        assertThatCode(() -> salonService.deactivateSalon(ownerId, salonId))
                .as("purgeSalonMediaAfterCommit wraps the whole afterCommit body in try/catch — "
                        + "the owner's DELETE already committed by the time this callback runs, "
                        + "so a downstream media-purge exception must never escape here")
                .doesNotThrowAnyException();

        assertThat(isSalonActive(salonId))
                .as("the deactivation itself (already committed before this callback even runs) "
                        + "is unaffected by the media-purge failure")
                .isFalse();
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────

    private UUID createOwner() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'SALON_OWNER', true, true)",
                id, "smpf-owner-" + System.nanoTime() + "@beautica.test", passwordEncoder.encode(TEST_PASSWORD));
        return id;
    }

    private boolean isSalonActive(UUID salonId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT is_active FROM salons WHERE id = ?", Boolean.class, salonId));
    }
}
