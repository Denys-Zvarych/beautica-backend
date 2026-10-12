package com.beautica.media.repository;

import com.beautica.AbstractDataJpaTest;
import com.beautica.auth.Role;
import com.beautica.media.entity.EntityType;
import com.beautica.media.entity.MediaFile;
import com.beautica.media.entity.MediaType;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.salon.entity.Salon;
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

import java.math.BigDecimal;
import java.util.List;
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
 *   <li>the account-deletion finders are keyed on the owning entity only, never on
 *       {@code uploader_id} (Phase 363).</li>
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
    @DisplayName("Phase 363: findAccountMediaKeys reads the account's USER rows and its masters' MASTER rows — "
            + "never a SALON row, even one the same user uploaded")
    void should_readOnlyOwnEntityRows_when_findAccountMediaKeys() {
        UUID masterId = UUID.randomUUID();
        MediaFile own = buildMedia(uploader, EntityType.USER, uploader.getId(), MediaType.AVATAR);
        MediaFile portfolio = buildMedia(uploader, EntityType.MASTER, masterId, MediaType.PORTFOLIO);
        MediaFile salonPhoto = buildMedia(uploader, EntityType.SALON, UUID.randomUUID(), MediaType.PORTFOLIO);
        MediaFile foreign = buildMedia(uploader, EntityType.MASTER, UUID.randomUUID(), MediaType.PORTFOLIO);
        em.persist(own);
        em.persist(portfolio);
        em.persist(salonPhoto);
        em.persist(foreign);
        em.flush();
        em.clear();

        List<MediaFileKey> keys = repo.findAccountMediaKeys(List.of(uploader.getId()), List.of(masterId));

        assertThat(keys).extracting(MediaFileKey::id).containsExactlyInAnyOrder(own.getId(), portfolio.getId());
    }

    @Test
    @DisplayName("Phase 363: deleteAccountMedia removes exactly the account's own rows and leaves SALON rows alone")
    void should_deleteOnlyOwnEntityRows_when_deleteAccountMedia() {
        UUID masterId = UUID.randomUUID();
        MediaFile portfolio = buildMedia(uploader, EntityType.MASTER, masterId, MediaType.PORTFOLIO);
        MediaFile salonPhoto = buildMedia(uploader, EntityType.SALON, UUID.randomUUID(), MediaType.PORTFOLIO);
        em.persist(portfolio);
        em.persist(salonPhoto);
        em.flush();
        em.clear();

        int deleted = repo.deleteAccountMedia(List.of(uploader.getId()), List.of(masterId));

        assertThat(deleted).isEqualTo(1);
        assertThat(repo.findById(portfolio.getId())).isEmpty();
        assertThat(repo.findById(salonPhoto.getId())).isPresent();
    }

    @Test
    @DisplayName("Phase 363: an empty master-id list is a valid bind (client accounts have no masters row)")
    void should_acceptEmptyMasterIds_when_findAccountMediaKeys() {
        List<MediaFileKey> keys = repo.findAccountMediaKeys(List.of(uploader.getId()), List.of());

        assertThat(keys).isEmpty();
    }

    @Test
    @DisplayName("Phase 362 F2: countPortfolioOfActiveSalon counts PORTFOLIO rows of an active salon, "
            + "and is empty for an inactive or unknown salon")
    void should_countPortfolio_when_salonActive_andBeEmpty_otherwise() {
        Salon active = persistSalon(true);
        Salon inactive = persistSalon(false);
        em.persist(buildMedia(uploader, EntityType.SALON, active.getId(), MediaType.PORTFOLIO));
        em.persist(buildMedia(uploader, EntityType.SALON, active.getId(), MediaType.PORTFOLIO));
        em.persist(buildMedia(uploader, EntityType.MASTER, active.getId(), MediaType.PORTFOLIO));
        em.flush();
        em.clear();

        assertThat(repo.countPortfolioOfActiveSalon(active.getId())).contains(2L);
        assertThat(repo.countPortfolioOfActiveSalon(inactive.getId())).isEmpty();
        assertThat(repo.countPortfolioOfActiveSalon(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("Phase 362 G3: findPortfolioRowOfActiveSalon loads only this active salon's PORTFOLIO row — "
            + "another salon's row, a non-portfolio row, a MASTER row and an inactive salon all come back empty")
    void should_findOnlyOwnPortfolioRow_when_salonActive() {
        Salon salonA = persistSalon(true);
        Salon salonB = persistSalon(true);
        Salon inactive = persistSalon(false);
        MediaFile own = buildMedia(uploader, EntityType.SALON, salonA.getId(), MediaType.PORTFOLIO);
        MediaFile otherSalons = buildMedia(uploader, EntityType.SALON, salonB.getId(), MediaType.PORTFOLIO);
        MediaFile avatar = buildMedia(uploader, EntityType.SALON, salonA.getId(), MediaType.AVATAR);
        MediaFile masterTyped = buildMedia(uploader, EntityType.MASTER, salonA.getId(), MediaType.PORTFOLIO);
        MediaFile ofInactive = buildMedia(uploader, EntityType.SALON, inactive.getId(), MediaType.PORTFOLIO);
        List.of(own, otherSalons, avatar, masterTyped, ofInactive).forEach(em::persist);
        em.flush();
        em.clear();

        assertThat(repo.findPortfolioRowOfActiveSalon(own.getId(), salonA.getId()))
                .map(MediaFile::getId).contains(own.getId());
        assertThat(repo.findPortfolioRowOfActiveSalon(otherSalons.getId(), salonA.getId())).isEmpty();
        assertThat(repo.findPortfolioRowOfActiveSalon(avatar.getId(), salonA.getId())).isEmpty();
        assertThat(repo.findPortfolioRowOfActiveSalon(masterTyped.getId(), salonA.getId())).isEmpty();
        assertThat(repo.findPortfolioRowOfActiveSalon(ofInactive.getId(), inactive.getId())).isEmpty();
        assertThat(repo.findPortfolioRowOfActiveSalon(UUID.randomUUID(), salonA.getId())).isEmpty();
    }

    @Test
    @DisplayName("Phase 362 H3: findPortfolioRowOfMasterUser loads only the caller's own master PORTFOLIO row — "
            + "another master's row, a SALON row, an avatar row and an unknown id all come back empty")
    void should_findOnlyOwnMasterPortfolioRow_when_scopedByUser() {
        User ownerUser = persistUser(Role.INDEPENDENT_MASTER);
        User otherUser = persistUser(Role.INDEPENDENT_MASTER);
        Master ownMaster = persistIndependentMaster(ownerUser);
        Master otherMaster = persistIndependentMaster(otherUser);
        Salon salon = persistSalon(true);
        MediaFile own = buildMedia(uploader, EntityType.MASTER, ownMaster.getId(), MediaType.PORTFOLIO);
        MediaFile othersRow = buildMedia(ownerUser, EntityType.MASTER, otherMaster.getId(), MediaType.PORTFOLIO);
        MediaFile salonRow = buildMedia(ownerUser, EntityType.SALON, salon.getId(), MediaType.PORTFOLIO);
        MediaFile avatar = buildMedia(ownerUser, EntityType.USER, ownerUser.getId(), MediaType.AVATAR);
        List.of(own, othersRow, salonRow, avatar).forEach(em::persist);
        em.flush();
        em.clear();

        assertThat(repo.findPortfolioRowOfMasterUser(own.getId(), ownerUser.getId()))
                .as("own row — matched by owning entity, not uploader")
                .map(MediaFile::getId).contains(own.getId());
        assertThat(repo.findPortfolioRowOfMasterUser(othersRow.getId(), ownerUser.getId()))
                .as("another master's row (uploaded by the caller)").isEmpty();
        assertThat(repo.findPortfolioRowOfMasterUser(salonRow.getId(), ownerUser.getId())).as("salon row").isEmpty();
        assertThat(repo.findPortfolioRowOfMasterUser(avatar.getId(), ownerUser.getId())).as("avatar row").isEmpty();
        assertThat(repo.findPortfolioRowOfMasterUser(UUID.randomUUID(), ownerUser.getId())).as("unknown id").isEmpty();
    }

    private User persistUser(Role role) {
        User user = new User(
                "media-m-" + UUID.randomUUID() + "@test.com",
                new BCryptPasswordEncoder(4).encode("test-password"),
                role,
                "Media",
                "Master",
                "+380501111112"
        );
        em.persist(user);
        return user;
    }

    private Master persistIndependentMaster(User user) {
        Master master = Master.builder()
                .user(user)
                .masterType(MasterType.INDEPENDENT_MASTER)
                .avgRating(BigDecimal.ZERO)
                .reviewCount(0)
                .isActive(true)
                .build();
        em.persist(master);
        return master;
    }

    private Salon persistSalon(boolean active) {
        Salon salon = Salon.builder()
                .cityId(testCityId())
                .owner(uploader)
                .name("Media Salon")
                .isActive(active)
                .build();
        em.persist(salon);
        return salon;
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
