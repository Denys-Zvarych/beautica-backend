package com.beautica.media.repository;

import com.beautica.AbstractDataJpaTest;
import com.beautica.auth.Role;
import com.beautica.media.entity.EntityType;
import com.beautica.media.entity.MediaFile;
import com.beautica.media.entity.MediaType;
import com.beautica.user.User;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import jakarta.persistence.PersistenceException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Repository-layer tests for {@link MediaRepository}.
 *
 * <p>Extends {@link AbstractDataJpaTest} so the PostgreSQL container is shared across
 * {@code @DataJpaTest} slice tests within the JVM. Slice annotations
 * ({@code @DataJpaTest}, {@code @AutoConfigureTestDatabase}, {@code @ActiveProfiles})
 * live on the base class.
 *
 * <p>Pins three contracts that are otherwise implicit:
 * <ul>
 *   <li>{@code findByEntityTypeAndEntityId} filters by both columns — same {@code entity_id}
 *       under a different {@code entity_type} must not bleed across.</li>
 *   <li>{@code findByUploaderIdAndMediaType} is declared as {@link Optional} despite no DB
 *       UNIQUE constraint on {@code (uploader_id, media_type)}, so multiple AVATAR rows
 *       for the same uploader must surface as
 *       {@link IncorrectResultSizeDataAccessException} — pinned here so a future schema
 *       change (partial unique index) is a deliberate, test-visible decision.</li>
 *   <li>{@code uploader} is {@code FetchType.LAZY} — the entity-type/entity-id finder
 *       must not eagerly load the {@link User} proxy.</li>
 * </ul>
 */
class MediaRepositoryTest extends AbstractDataJpaTest {

    @Autowired
    private MediaRepository repo;

    @Autowired
    private TestEntityManager em;

    @Autowired
    private EntityManagerFactory emf;

    private User uploader;
    private Statistics statistics;

    @BeforeEach
    void setUp() {
        uploader = new User(
                "media-" + UUID.randomUUID() + "@test.com",
                new BCryptPasswordEncoder(4).encode("test-password"),
                Role.CLIENT,
                "Media",
                "User",
                "+380501111111"
        );
        em.persist(uploader);
        em.flush();

        // Pull Hibernate Statistics from the shared SessionFactory so the LAZY-fetch test
        // can prove findByEntityTypeAndEntityId loads only MediaFile rows, not the User proxy.
        SessionFactory sessionFactory = emf.unwrap(SessionFactory.class);
        statistics = sessionFactory.getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
    }

    private MediaFile buildMedia(User uploaderUser, EntityType entityType, UUID entityId, MediaType mediaType) {
        return MediaFile.builder()
                .uploader(uploaderUser)
                .entityType(entityType)
                .entityId(entityId)
                .mediaType(mediaType)
                .r2Key("r2-key-" + UUID.randomUUID())
                .r2Url("https://r2.example.com/" + UUID.randomUUID())
                .build();
    }

    @Test
    @DisplayName("Returns an empty list when no media rows exist for the given (entity_type, entity_id) pair")
    void should_returnEmpty_when_noMediaForEntityType() {
        UUID randomEntityId = UUID.randomUUID();

        List<MediaFile> result = repo.findByEntityTypeAndEntityId(EntityType.USER, randomEntityId);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Filters by entity_type even when the same entity_id UUID exists under a different type — rows must not bleed")
    void should_filterByEntityType_when_sameEntityIdExistsAcrossTypes() {
        UUID sharedEntityId = UUID.randomUUID();
        MediaFile userMedia = buildMedia(uploader, EntityType.USER, sharedEntityId, MediaType.AVATAR);
        MediaFile masterMedia = buildMedia(uploader, EntityType.MASTER, sharedEntityId, MediaType.PORTFOLIO);
        em.persist(userMedia);
        em.persist(masterMedia);
        em.flush();
        em.clear();

        List<MediaFile> userResult = repo.findByEntityTypeAndEntityId(EntityType.USER, sharedEntityId);
        List<MediaFile> masterResult = repo.findByEntityTypeAndEntityId(EntityType.MASTER, sharedEntityId);

        assertThat(userResult).hasSize(1);
        assertThat(userResult.get(0).getEntityType()).isEqualTo(EntityType.USER);
        assertThat(masterResult).hasSize(1);
        assertThat(masterResult.get(0).getEntityType()).isEqualTo(EntityType.MASTER);
    }

    @Test
    @DisplayName("Returns Optional.empty() when the uploader has no AVATAR row")
    void should_returnEmpty_when_uploaderHasNoAvatar() {
        Optional<MediaFile> result = repo.findByUploaderIdAndMediaType(uploader.getId(), MediaType.AVATAR);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Returns the single AVATAR row when the uploader has exactly one")
    void should_returnAvatar_when_uploaderHasOneAvatar() {
        MediaFile avatar = buildMedia(uploader, EntityType.USER, uploader.getId(), MediaType.AVATAR);
        em.persist(avatar);
        em.flush();
        em.clear();

        Optional<MediaFile> result = repo.findByUploaderIdAndMediaType(uploader.getId(), MediaType.AVATAR);

        assertThat(result).isPresent();
        assertThat(result.get().getMediaType()).isEqualTo(MediaType.AVATAR);
        assertThat(result.get().getId()).isEqualTo(avatar.getId());
    }

    @Test
    @DisplayName("Rejects a second AVATAR for the same (entity_type, entity_id) — ux_media_files_avatar partial unique index (V39)")
    void should_rejectDuplicateAvatar_when_sameEntityAlreadyHasOne() {
        // Arrange — first avatar persisted successfully.
        MediaFile first = buildMedia(uploader, EntityType.USER, uploader.getId(), MediaType.AVATAR);
        em.persist(first);
        em.flush();

        // Act + Assert — inserting a second AVATAR for the same entity must be rejected at
        // the DB layer by the partial unique index added in V39. TestEntityManager.flush()
        // bypasses Spring Data's exception translation, so Hibernate surfaces the violation
        // as jakarta.persistence.PersistenceException wrapping ConstraintViolationException.
        MediaFile second = buildMedia(uploader, EntityType.USER, uploader.getId(), MediaType.AVATAR);
        assertThatThrownBy(() -> {
            em.persist(second);
            em.flush();
        }).isInstanceOf(PersistenceException.class);
    }

    @Test
    @DisplayName("Returns all rows for an entity regardless of media_type — finder does NOT filter by media_type (3 PORTFOLIO + 1 AVATAR = 4 rows)")
    void should_returnAllPortfolioAndAvatarRows_when_findByEntityTypeAndEntityId() {
        UUID masterEntityId = UUID.randomUUID();
        em.persist(buildMedia(uploader, EntityType.MASTER, masterEntityId, MediaType.PORTFOLIO));
        em.persist(buildMedia(uploader, EntityType.MASTER, masterEntityId, MediaType.PORTFOLIO));
        em.persist(buildMedia(uploader, EntityType.MASTER, masterEntityId, MediaType.PORTFOLIO));
        em.persist(buildMedia(uploader, EntityType.MASTER, masterEntityId, MediaType.AVATAR));
        em.flush();
        em.clear();

        List<MediaFile> result = repo.findByEntityTypeAndEntityId(EntityType.MASTER, masterEntityId);

        assertThat(result).hasSize(4);
        assertThat(result).extracting(MediaFile::getMediaType)
                .containsExactlyInAnyOrder(
                        MediaType.PORTFOLIO,
                        MediaType.PORTFOLIO,
                        MediaType.PORTFOLIO,
                        MediaType.AVATAR
                );
    }

    @Test
    @DisplayName("Rejects r2_key containing path-traversal sequences — chk_media_files_r2_key_shape (V39)")
    void should_rejectInsert_when_r2KeyContainsPathTraversal() {
        MediaFile bad = MediaFile.builder()
                .uploader(uploader)
                .entityType(EntityType.USER)
                .entityId(uploader.getId())
                .mediaType(MediaType.AVATAR)
                .r2Key("avatars/../../../etc/passwd")
                .r2Url("https://cdn.example.com/valid.jpg")
                .build();

        assertThatThrownBy(() -> {
            em.persist(bad);
            em.flush();
        }).isInstanceOf(PersistenceException.class);
    }

    @Test
    @DisplayName("Rejects non-HTTPS r2_url — chk_media_files_r2_url_scheme (V39)")
    void should_rejectInsert_when_r2UrlIsHttp() {
        MediaFile bad = MediaFile.builder()
                .uploader(uploader)
                .entityType(EntityType.USER)
                .entityId(uploader.getId())
                .mediaType(MediaType.AVATAR)
                .r2Key("avatars/valid-" + UUID.randomUUID() + ".jpg")
                .r2Url("http://cdn.example.com/avatar.jpg")
                .build();

        assertThatThrownBy(() -> {
            em.persist(bad);
            em.flush();
        }).isInstanceOf(PersistenceException.class);
    }

    @Test
    @DisplayName("Rejects PORTFOLIO media_type for USER entity — chk_media_files_media_type_entity_type (V39)")
    void should_rejectInsert_when_portfolioAssignedToUserEntity() {
        MediaFile bad = MediaFile.builder()
                .uploader(uploader)
                .entityType(EntityType.USER)
                .entityId(uploader.getId())
                .mediaType(MediaType.PORTFOLIO)
                .r2Key("portfolio/valid-" + UUID.randomUUID() + ".jpg")
                .r2Url("https://cdn.example.com/photo.jpg")
                .build();

        assertThatThrownBy(() -> {
            em.persist(bad);
            em.flush();
        }).isInstanceOf(PersistenceException.class);
    }

    @Test
    @DisplayName("Finds portfolio items for a SALON entity via findByEntityTypeAndEntityId")
    void should_findSalonPortfolioItems_when_entityTypeIsSalon() {
        // Arrange
        UUID salonId = UUID.randomUUID();
        MediaFile salonPhoto = MediaFile.builder()
                .uploader(uploader)
                .entityType(EntityType.SALON)
                .entityId(salonId)
                .mediaType(MediaType.PORTFOLIO)
                .r2Key("portfolio/salon-photo-" + UUID.randomUUID() + ".jpg")
                .r2Url("https://cdn.example.com/salon-photo.jpg")
                .build();
        em.persist(salonPhoto);
        em.flush();
        em.clear();

        // Act
        List<MediaFile> results = repo.findByEntityTypeAndEntityId(EntityType.SALON, salonId);

        // Assert
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getEntityType()).isEqualTo(EntityType.SALON);
        assertThat(results.get(0).getEntityId()).isEqualTo(salonId);
        assertThat(results.get(0).getMediaType()).isEqualTo(MediaType.PORTFOLIO);
    }

    @Test
    @DisplayName("Does not eagerly initialise the uploader proxy when calling findByEntityTypeAndEntityId — Hibernate Statistics shows only MediaFile loaded")
    void should_notInitializeUploaderProxy_when_findByEntityTypeAndEntityId() {
        UUID masterEntityId = UUID.randomUUID();
        em.persist(buildMedia(uploader, EntityType.MASTER, masterEntityId, MediaType.PORTFOLIO));
        em.persist(buildMedia(uploader, EntityType.MASTER, masterEntityId, MediaType.PORTFOLIO));
        em.flush();
        em.clear();
        // Reset after setUp's user-persist fetch counters, so the count below reflects only the finder call.
        statistics.clear();

        List<MediaFile> result = repo.findByEntityTypeAndEntityId(EntityType.MASTER, masterEntityId);

        // Two MediaFile rows hydrated, zero User rows — proxy stays uninitialised.
        // Upper-bounded at the row count so a future eager misconfiguration (which would
        // also trigger N User loads) trips this assertion.
        assertThat(result).hasSize(2);
        assertThat(statistics.getEntityFetchCount()).isLessThanOrEqualTo(2);
        assertThat(statistics.getEntityLoadCount()).isEqualTo(2);
    }

    // ── Paginated overload: findByEntityTypeAndEntityId(EntityType, UUID, Pageable) ─
    // These tests cover the new three-argument overload added in the Phase 9.6
    // hardening pass. The controller dispatches to this overload on every public
    // GET portfolio request — a broken derived query would not surface without
    // repository-level assertions.

    @Test
    @DisplayName("Paginated finder returns empty Page when no rows exist for (entity_type, entity_id)")
    void should_returnEmptyPage_when_noMediaExistsForEntity_paginated() {
        UUID entityId = UUID.randomUUID();

        Page<MediaFile> result = repo.findByEntityTypeAndEntityId(
                EntityType.SALON, entityId, PageRequest.of(0, 20));

        assertThat(result.getContent())
                .as("content must be empty when no rows exist")
                .isEmpty();
        assertThat(result.getTotalElements())
                .as("totalElements must be 0")
                .isZero();
        assertThat(result.getTotalPages())
                .as("totalPages must be 0")
                .isZero();
    }

    @Test
    @DisplayName("Paginated finder returns correct content and metadata when rows exist")
    void should_returnPageWithCorrectMetadata_when_rowsExist_paginated() {
        UUID salonId = UUID.randomUUID();
        em.persist(buildMedia(uploader, EntityType.SALON, salonId, MediaType.PORTFOLIO));
        em.persist(buildMedia(uploader, EntityType.SALON, salonId, MediaType.PORTFOLIO));
        em.persist(buildMedia(uploader, EntityType.SALON, salonId, MediaType.PORTFOLIO));
        em.flush();
        em.clear();

        Page<MediaFile> result = repo.findByEntityTypeAndEntityId(
                EntityType.SALON, salonId, PageRequest.of(0, 20));

        assertThat(result.getContent())
                .as("content must contain all 3 items on page 0 with size=20")
                .hasSize(3);
        assertThat(result.getTotalElements())
                .as("totalElements must be 3")
                .isEqualTo(3L);
        assertThat(result.getTotalPages())
                .as("totalPages must be 1")
                .isEqualTo(1);
        assertThat(result.getContent())
                .allSatisfy(m -> assertThat(m.getEntityType())
                        .as("every item must be typed SALON")
                        .isEqualTo(EntityType.SALON));
    }

    @Test
    @DisplayName("Paginated finder returns page 1 items correctly when result set spans two pages")
    void should_returnSecondPageItems_when_resultSetSpansTwoPages_paginated() {
        // Arrange — 3 rows, page size 2 → page 0 has 2 items, page 1 has 1 item.
        UUID masterId = UUID.randomUUID();
        MediaFile first  = buildMedia(uploader, EntityType.MASTER, masterId, MediaType.PORTFOLIO);
        MediaFile second = buildMedia(uploader, EntityType.MASTER, masterId, MediaType.PORTFOLIO);
        MediaFile third  = buildMedia(uploader, EntityType.MASTER, masterId, MediaType.PORTFOLIO);
        em.persist(first);
        em.persist(second);
        em.persist(third);
        em.flush();
        em.clear();

        // Act — request page 1 (zero-indexed) with size 2
        Page<MediaFile> page1 = repo.findByEntityTypeAndEntityId(
                EntityType.MASTER, masterId, PageRequest.of(1, 2));

        // Assert — offset arithmetic must be correct, not always returning page 0
        assertThat(page1.getContent())
                .as("page 1 must contain exactly 1 item when there are 3 total with page size 2")
                .hasSize(1);
        assertThat(page1.getTotalElements())
                .as("totalElements must be 3 regardless of which page is fetched")
                .isEqualTo(3L);
        assertThat(page1.getTotalPages())
                .as("totalPages must be 2")
                .isEqualTo(2);
        assertThat(page1.isLast())
                .as("page 1 must be the last page")
                .isTrue();
    }

    @Test
    @DisplayName("Paginated finder does not bleed across entity types when the same UUID exists under another type")
    void should_notBleedAcrossEntityTypes_when_sameEntityIdExistsUnderAnotherType_paginated() {
        UUID sharedId = UUID.randomUUID();
        em.persist(buildMedia(uploader, EntityType.SALON, sharedId, MediaType.PORTFOLIO));
        em.persist(buildMedia(uploader, EntityType.SALON, sharedId, MediaType.PORTFOLIO));
        em.persist(buildMedia(uploader, EntityType.MASTER, sharedId, MediaType.PORTFOLIO));
        em.flush();
        em.clear();

        Page<MediaFile> salonPage = repo.findByEntityTypeAndEntityId(
                EntityType.SALON, sharedId, PageRequest.of(0, 20));
        Page<MediaFile> masterPage = repo.findByEntityTypeAndEntityId(
                EntityType.MASTER, sharedId, PageRequest.of(0, 20));

        assertThat(salonPage.getTotalElements())
                .as("SALON query must return only the 2 SALON rows")
                .isEqualTo(2L);
        assertThat(masterPage.getTotalElements())
                .as("MASTER query must return only the 1 MASTER row")
                .isEqualTo(1L);
        assertThat(salonPage.getContent())
                .allSatisfy(m -> assertThat(m.getEntityType()).isEqualTo(EntityType.SALON));
        assertThat(masterPage.getContent())
                .allSatisfy(m -> assertThat(m.getEntityType()).isEqualTo(EntityType.MASTER));
    }
}
