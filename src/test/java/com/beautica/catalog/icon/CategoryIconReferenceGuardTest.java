package com.beautica.catalog.icon;

import com.beautica.service.repository.PlatformCategoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CategoryIconReferenceGuardTest {

    private static final String KEY = "catalog/category-icons/MANICURE/0123456789abcdef.svg";
    private static final long CLAIM_BLOCKED_PROBE_MS = 200;

    private final PlatformCategoryRepository repository = mock(PlatformCategoryRepository.class);
    private final CategoryIconReferenceGuard guard = new CategoryIconReferenceGuard(repository);

    @Test
    @DisplayName("a claimed key is referenced with no committed row; each claim needs its own release")
    void should_keepKeyReferenced_until_everyClaimReleased() {
        guard.claim(KEY);
        guard.claim(KEY);

        guard.release(KEY);
        boolean afterOneRelease = guard.isReferenced(KEY);
        guard.release(KEY);

        assertThat(afterOneRelease).isTrue();
        assertThat(guard.isReferenced(KEY)).isFalse();
    }

    @Test
    @DisplayName("a committed row keeps the key referenced without any claim")
    void should_reportReferenced_when_committedRowPointsAtKey() {
        when(repository.existsByIconKey(KEY)).thenReturn(true);

        assertThat(guard.isReferenced(KEY)).isTrue();
    }

    @Test
    @DisplayName("a claim made while a purge is between its check and its delete waits for that purge to finish, so "
            + "the claimer's PUT re-creates the object after the delete")
    void should_blockClaim_while_purgeSectionRuns() throws Exception {
        AtomicBoolean claimedInsideSection = new AtomicBoolean();
        Thread claimer = new Thread(() -> guard.claim(KEY));

        guard.exclusively(() -> {
            claimer.start();
            try {
                claimer.join(CLAIM_BLOCKED_PROBE_MS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            claimedInsideSection.set(!claimer.isAlive());
        });
        claimer.join();

        assertThat(claimedInsideSection).isFalse();
        assertThat(guard.isReferenced(KEY)).isTrue();
    }

    @Test
    @DisplayName("keys outside the category-icon prefix are not guarded")
    void should_applyOnlyToCategoryIconKeys() {
        assertThat(guard.appliesTo(KEY)).isTrue();
        assertThat(guard.appliesTo("media/salon/x.jpg")).isFalse();
        assertThat(guard.appliesTo(null)).isFalse();
    }
}
