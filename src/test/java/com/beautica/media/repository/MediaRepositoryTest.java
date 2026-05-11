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
import org.springframework.dao.IncorrectResultSizeDataAccessException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
                "$2a$10$hash",
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
    @DisplayName("Throws IncorrectResultSizeDataAccessException when two AVATAR rows exist for the same uploader — multi-AVATAR is legal at the DB layer today (no partial UNIQUE), so the Optional contract surfaces via Spring Data's translation")
    void should_throwIncorrectResultSize_when_uploaderHasMultipleAvatars() {
        MediaFile first = buildMedia(uploader, EntityType.USER, uploader.getId(), MediaType.AVATAR);
        MediaFile second = buildMedia(uploader, EntityType.USER, uploader.getId(), MediaType.AVATAR);
        em.persist(first);
        em.persist(second);
        em.flush();
        em.clear();

        assertThatThrownBy(() -> repo.findByUploaderIdAndMediaType(uploader.getId(), MediaType.AVATAR))
                .isInstanceOf(IncorrectResultSizeDataAccessException.class);
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
}
