package com.beautica.media;

import com.beautica.config.TestSecurityConfig;
import com.beautica.media.entity.EntityType;
import com.beautica.media.repository.MediaFileKey;
import com.beautica.media.repository.MediaRepository;
import com.beautica.media.service.MediaService;
import com.beautica.media.service.R2StorageService;
import com.beautica.support.HibernateStatistics;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * PERF-1: the shared {@code sweepBlobs} DB step (reached through {@link MediaService#deleteBySalon}, its only
 * entry point) deletes its {@code media_files} rows with ONE batched {@code DELETE ... WHERE id IN (...)}. The
 * former {@code deleteAll(rows)} on DETACHED rows merged each row first — one SELECT per row (N+1). Pinned
 * against real Postgres via Hibernate's statement counter: the statement count of a sweep must not grow with
 * the number of rows swept.
 */
@Import(TestSecurityConfig.class)
@DisplayName("MediaService sweep — batched media_files delete (PERF-1)")
class MediaSweepBatchDeleteIT extends AbstractMediaIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private MediaService mediaService;
    @Autowired private MediaRepository mediaRepository;
    @Autowired private EntityManagerFactory emf;

    @MockBean private R2StorageService r2;

    @Override protected TestRestTemplate restTemplate() { return restTemplate; }
    @Override protected ObjectMapper objectMapper() { return objectMapper; }
    @Override protected PasswordEncoder passwordEncoder() { return passwordEncoder; }

    @BeforeEach
    void setUpR2() {
        reset(r2);
        when(r2.isEnabled()).thenReturn(true);
        when(r2.deleteFiles(anyList())).thenReturn(Set.of());
    }

    @Test
    @DisplayName("sweeping 1 row and 5 rows issues the same number of statements, and every row is gone")
    void should_issueConstantStatementCount_when_sweepingManyRows() {
        UUID oneRowSalon = salonWithPortfolioRows("sweep-one", 1);
        UUID fiveRowSalon = salonWithPortfolioRows("sweep-five", 5);
        List<MediaFileKey> oneRow = mediaRepository.findMediaKeysByEntityTypeAndEntityId(EntityType.SALON, oneRowSalon);
        List<MediaFileKey> fiveRows =
                mediaRepository.findMediaKeysByEntityTypeAndEntityId(EntityType.SALON, fiveRowSalon);
        Statistics statistics = HibernateStatistics.enabledOn(emf);

        statistics.clear();
        mediaService.deleteBySalon(oneRowSalon, List.of(), oneRow);
        long statementsForOne = statistics.getPrepareStatementCount();
        statistics.clear();
        mediaService.deleteBySalon(fiveRowSalon, List.of(), fiveRows);
        long statementsForFive = statistics.getPrepareStatementCount();

        assertThat(oneRow).hasSize(1);
        assertThat(fiveRows).hasSize(5);
        assertThat(statementsForFive)
                .as("a per-row SELECT (merge of a detached row) would add 4 statements for 4 extra rows")
                .isEqualTo(statementsForOne);
        assertThat(mediaRowCount(oneRowSalon)).isZero();
        assertThat(mediaRowCount(fiveRowSalon)).isZero();
    }

    private UUID salonWithPortfolioRows(String tag, int rows) {
        UUID ownerId = insertSalonOwner(tag + "-" + UUID.randomUUID() + "@beautica.test");
        UUID salonId = insertSalon(ownerId, "Salon " + tag);
        for (int i = 0; i < rows; i++) {
            String key = "portfolio/salons/" + salonId + "/m-" + UUID.randomUUID() + ".jpg";
            jdbcTemplate.update(
                    "INSERT INTO media_files (id, uploader_id, entity_type, entity_id, media_type, r2_key, r2_url, "
                            + "created_at, updated_at) VALUES (?, ?, 'SALON', ?, 'PORTFOLIO', ?, ?, NOW(), NOW())",
                    UUID.randomUUID(), ownerId, salonId, key, "https://cdn.example/" + key);
        }
        return salonId;
    }

    private int mediaRowCount(UUID salonId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM media_files WHERE entity_id = ?", Integer.class, salonId);
        return count == null ? 0 : count;
    }
}
