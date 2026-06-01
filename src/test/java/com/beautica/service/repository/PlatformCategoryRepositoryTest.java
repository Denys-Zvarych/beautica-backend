package com.beautica.service.repository;

import com.beautica.AbstractDataJpaTest;
import com.beautica.service.entity.PlatformCategory;
import com.beautica.service.entity.PlatformCategoryStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository-slice tests for {@link PlatformCategoryRepository} against a real
 * PostgreSQL container (shared via {@link AbstractDataJpaTest}).
 *
 * <p>Pins the derived-query contracts that the JPQL/derived method names imply but
 * which are otherwise only exercised through service mocks:
 * <ul>
 *   <li>{@code findApprovedActive} — filters to {@code APPROVED AND active} and orders
 *       by {@code displayName}.</li>
 *   <li>{@code findByTokenHash} — exact hash match for the approve/reject lookup.</li>
 *   <li>{@code existsByNameIgnoreCaseAndStatusIn} (ignore-case) vs
 *       {@code existsByNameAndActiveTrueAndStatus} (exact-case) — the case sensitivity
 *       contract is the difference between the conflict guard and the usability gate.</li>
 * </ul>
 *
 * <p>Each test first clears the 7 Flyway-seeded categories inside the rolled-back
 * test transaction so assertions are deterministic; the seeds are restored on rollback.
 */
@DisplayName("PlatformCategoryRepository — @DataJpaTest")
class PlatformCategoryRepositoryTest extends AbstractDataJpaTest {

    @Autowired private PlatformCategoryRepository repository;
    @Autowired private TestEntityManager em;

    @BeforeEach
    void clearSeeds() {
        em.getEntityManager().createQuery("DELETE FROM PlatformCategory").executeUpdate();
        em.flush();
    }

    private PlatformCategory approved(String name, String displayName) {
        return persist(PlatformCategory.ofApproved(name, displayName));
    }

    private PlatformCategory persist(PlatformCategory category) {
        em.persist(category);
        em.flush();
        return category;
    }

    private PlatformCategory pending(String name, String displayName, String tokenHash) {
        OffsetDateTime now = OffsetDateTime.now();
        return persist(PlatformCategory.ofPendingRequest(
                name, displayName, null, tokenHash, now, now.plusDays(7)));
    }

    // ── findApprovedActive ─────────────────────────────────────────────────────

    @Test
    @DisplayName("returns only APPROVED+active rows ordered by displayName")
    void should_returnApprovedActiveOrderedByDisplayName_when_findApprovedActive() {
        approved("ZED", "Аква");          // displayName sorts first
        approved("ALPHA", "Манікюр");     // displayName sorts last
        pending("PENDING_ONE", "Очікує", "a".repeat(64));  // PENDING — must be excluded

        List<PlatformCategory> result = repository.findApprovedActive();

        assertThat(result)
                .extracting(PlatformCategory::getName)
                .as("PENDING rows excluded; ordered by displayName, not name")
                .containsExactly("ZED", "ALPHA");
    }

    @Test
    @DisplayName("excludes an APPROVED-but-inactive row")
    void should_excludeInactive_when_findApprovedActive() {
        // The entity exposes no APPROVED+inactive transition (reject() sets REJECTED),
        // so we model a non-(APPROVED+active) row via REJECTED — active=false — which the
        // findApprovedActive predicate must likewise exclude.
        PlatformCategory inactive = PlatformCategory.ofApproved("RETIRED", "Архів");
        inactive.reject(OffsetDateTime.now());
        persist(inactive);
        approved("LIVE", "Живий");

        List<PlatformCategory> result = repository.findApprovedActive();

        assertThat(result)
                .extracting(PlatformCategory::getName)
                .as("only the APPROVED+active row is returned")
                .containsExactly("LIVE");
    }

    // ── findByTokenHash ────────────────────────────────────────────────────────

    @Test
    @DisplayName("returns the PENDING row matching the exact token hash")
    void should_returnRow_when_findByTokenHashMatches() {
        String hash = "b".repeat(64);
        pending("TOKENED", "Токен", hash);

        Optional<PlatformCategory> found = repository.findByTokenHash(hash);

        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("TOKENED");
    }

    @Test
    @DisplayName("returns empty when no row carries the token hash")
    void should_returnEmpty_when_findByTokenHashMisses() {
        pending("TOKENED", "Токен", "c".repeat(64));

        Optional<PlatformCategory> found = repository.findByTokenHash("d".repeat(64));

        assertThat(found).isEmpty();
    }

    // ── case-sensitivity contract ──────────────────────────────────────────────

    @Test
    @DisplayName("existsByNameIgnoreCaseAndStatusIn matches regardless of case")
    void should_matchIgnoreCase_when_existsByNameIgnoreCaseAndStatusIn() {
        approved("MANICURE", "Манікюр");

        boolean matchesLower = repository.existsByNameIgnoreCaseAndStatusIn(
                "manicure", List.of(PlatformCategoryStatus.APPROVED, PlatformCategoryStatus.PENDING));

        assertThat(matchesLower)
                .as("ignore-case conflict guard must match a lowercased name")
                .isTrue();
    }

    @Test
    @DisplayName("existsByNameIgnoreCaseAndStatusIn does not match a REJECTED-only name")
    void should_notMatchRejected_when_existsByNameIgnoreCaseAndStatusIn() {
        PlatformCategory rejected = PlatformCategory.ofApproved("REJECTED_NAME", "Відхилено");
        rejected.reject(OffsetDateTime.now());
        persist(rejected);

        boolean blocked = repository.existsByNameIgnoreCaseAndStatusIn(
                "rejected_name", List.of(PlatformCategoryStatus.APPROVED, PlatformCategoryStatus.PENDING));

        assertThat(blocked)
                .as("REJECTED is outside the {APPROVED,PENDING} set — resubmission must be allowed")
                .isFalse();
    }

    @Test
    @DisplayName("existsByNameAndActiveTrueAndStatus is exact-case (the usability gate)")
    void should_beExactCase_when_existsByNameAndActiveTrueAndStatus() {
        approved("MANICURE", "Манікюр");

        boolean exactMatch = repository.existsByNameAndActiveTrueAndStatus(
                "MANICURE", PlatformCategoryStatus.APPROVED);
        boolean wrongCase = repository.existsByNameAndActiveTrueAndStatus(
                "manicure", PlatformCategoryStatus.APPROVED);

        assertThat(exactMatch)
                .as("exact-case match must succeed for the stored uppercase wire name")
                .isTrue();
        assertThat(wrongCase)
                .as("the usability gate is exact-case — a differently-cased name must NOT pass")
                .isFalse();
    }

    @Test
    @DisplayName("existsByNameAndActiveTrueAndStatus returns false for a PENDING row")
    void should_returnFalse_when_categoryPendingForUsabilityGate() {
        pending("PENDING_GATE", "Очікує", "e".repeat(64));

        boolean selectable = repository.existsByNameAndActiveTrueAndStatus(
                "PENDING_GATE", PlatformCategoryStatus.APPROVED);

        assertThat(selectable)
                .as("a PENDING (inactive) category must not pass the APPROVED+active gate")
                .isFalse();
    }
}
