package com.beautica.media;

import com.beautica.config.TestSecurityConfig;
import com.beautica.media.entity.MediaFile;
import com.beautica.media.repository.MediaRepository;
import com.beautica.media.service.MediaService;
import com.beautica.media.service.R2StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 362 D6 — the portfolio cap holds under concurrent uploads.
 *
 * <p><b>How the race is forced (deterministic, no sleeps).</b> The salon is seeded with
 * {@code MAX - 1} photos and two uploads are fired in parallel. {@code mediaRepository.save} — reached only
 * AFTER the cap count, inside the write transaction — waits on a 2-party barrier (bounded at
 * {@link #BARRIER_WAIT_MS}) before inserting:
 * <ul>
 *   <li><b>With the salon row lock</b> the second upload is parked on {@code FOR NO KEY UPDATE} before its
 *       count, so it can never reach the barrier; the first one's wait times out, it inserts and commits, and
 *       the second then counts {@code MAX} → 409.</li>
 *   <li><b>Without the lock</b> (mutation: drop {@code findByIdForUpdate} from
 *       {@code MediaService#lockPortfolioOwner}) both uploads count {@code MAX - 1}, both meet at the barrier,
 *       both insert → {@code MAX + 1} rows, and this test fails.</li>
 * </ul>
 * The barrier wait (1 s) is well under the lock's 3 s {@code lock_timeout}, so the parked upload never times
 * out on the lock itself.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Portfolio cap — concurrency (Phase 362 D6)")
class PortfolioCapConcurrencyIT extends AbstractMediaIntegrationTest {

    private static final long BARRIER_WAIT_MS = 1_000;

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;

    // Same mock/spy set as MediaIntegrationTest so the Spring test context is shared.
    @MockBean private R2StorageService r2StorageService;
    @SpyBean private MediaRepository mediaRepository;

    @Override protected TestRestTemplate restTemplate() { return restTemplate; }
    @Override protected ObjectMapper objectMapper() { return objectMapper; }
    @Override protected PasswordEncoder passwordEncoder() { return passwordEncoder; }

    @BeforeEach
    void configureR2() {
        when(r2StorageService.isEnabled()).thenReturn(true);
        when(r2StorageService.buildPublicUrl(anyString())).thenAnswer(inv -> "https://cdn.example/" + inv.getArgument(0));
        doNothing().when(r2StorageService).uploadFile(anyString(), any(), anyLong(), anyString());
        doNothing().when(r2StorageService).deleteFile(anyString());
    }

    @Test
    @DisplayName("29 seeded + 2 parallel uploads → exactly one 201 and one 409, 30 rows, the rejected blob purged")
    void should_admitExactlyOneUpload_when_twoRaceForTheLastSlot() throws Exception {
        // Arrange
        String ownerEmail = "cap-race-" + System.nanoTime() + "@beautica.test";
        UUID ownerId = insertSalonOwner(ownerEmail);
        UUID salonId = insertSalon(ownerId, "Cap Race Salon");
        seedPortfolio(ownerId, salonId, MediaService.MAX_PORTFOLIO_PHOTOS - 1);
        String token = loginAndGetToken(ownerEmail);
        CyclicBarrier bothCounted = new CyclicBarrier(2);
        // A @SpyBean over a Spring Data (JDK-proxy) repository delegates through its default answer —
        // callRealMethod() is unavailable on an interface, so the real save is reached via that answer.
        Answer<?> realRepository = mockingDetails(mediaRepository).getMockCreationSettings().getDefaultAnswer();
        doAnswer(inv -> {
            awaitQuietly(bothCounted);
            return realRepository.answer(inv);
        }).when(mediaRepository).save(any(MediaFile.class));
        Callable<Integer> upload = () -> restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/portfolio", HttpMethod.POST,
                new HttpEntity<>(jpegMultipartBody(), bearerMultipartHeaders(token)), String.class)
                .getStatusCode().value();

        // Act
        List<Integer> statuses = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> futures = List.of(pool.submit(upload), pool.submit(upload));
            for (Future<Integer> f : futures) {
                statuses.add(f.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        // Assert
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(portfolioCount(salonId)).isEqualTo(MediaService.MAX_PORTFOLIO_PHOTOS);
        List<String> uploaded = invokedKeys("uploadFile");
        List<String> deleted = invokedKeys("deleteFile");
        assertThat(uploaded).hasSize(2);
        assertThat(deleted).as("exactly the rejected upload's blob is purged").hasSize(1);
        assertThat(uploaded).contains(deleted.get(0));
        assertThat(liveKeys(salonId)).doesNotContain(deleted.get(0));
        // Positive controls: the spy records both uploads' read-gate and the winner's save, so the never() below
        // is not vacuous.
        verify(mediaRepository, org.mockito.Mockito.times(2)).countPortfolioOfActiveSalon(salonId);
        verify(mediaRepository).save(any(MediaFile.class));
        verify(mediaRepository, never()).existsByEntityTypeAndEntityIdAndR2Key(any(), any(), anyString());
    }

    private static void awaitQuietly(CyclicBarrier barrier) throws InterruptedException {
        try {
            barrier.await(BARRIER_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException | BrokenBarrierException expectedWithTheLock) {
            // The other upload is parked on the salon row lock — proceed alone (see class Javadoc).
        }
    }

    private void seedPortfolio(UUID uploaderId, UUID salonId, int count) {
        for (int i = 0; i < count; i++) {
            String key = "portfolio/salons/" + salonId + "/seed-" + i + ".jpg";
            jdbcTemplate.update("""
                    INSERT INTO media_files
                      (id, uploader_id, entity_type, entity_id, media_type, r2_key, r2_url, created_at, updated_at)
                    VALUES (?, ?, 'SALON', ?, 'PORTFOLIO', ?, ?, NOW(), NOW())
                    """, UUID.randomUUID(), uploaderId, salonId, key, "https://cdn.example/" + key);
        }
    }

    private int portfolioCount(UUID salonId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM media_files WHERE entity_type = 'SALON' AND entity_id = ?",
                Integer.class, salonId);
    }

    private List<String> liveKeys(UUID salonId) {
        return jdbcTemplate.queryForList(
                "SELECT r2_key FROM media_files WHERE entity_type = 'SALON' AND entity_id = ?",
                String.class, salonId);
    }

    private List<String> invokedKeys(String method) {
        List<String> keys = new ArrayList<>();
        mockingDetails(r2StorageService).getInvocations().forEach(inv -> {
            if (inv.getMethod().getName().equals(method)) {
                keys.add(inv.getArgument(0));
            }
        });
        return keys;
    }
}
