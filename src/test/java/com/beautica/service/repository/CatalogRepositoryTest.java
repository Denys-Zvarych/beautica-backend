package com.beautica.service.repository;

import com.beautica.service.entity.CatalogCategory;
import com.beautica.service.entity.ServiceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class CatalogRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private CatalogCategoryRepository categoryRepository;

    @Autowired
    private ServiceTypeRepository serviceTypeRepository;

    @Autowired
    private TestEntityManager em;

    @BeforeEach
    void cleanDatabase() {
        serviceTypeRepository.deleteAllInBatch();
        categoryRepository.deleteAllInBatch();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private CatalogCategory persistCategory(String nameUk, String nameEn, int sortOrder) {
        var category = CatalogCategory.builder()
                .nameUk(nameUk)
                .nameEn(nameEn)
                .sortOrder(sortOrder)
                .build();
        return em.persist(category);
    }

    private ServiceType persistServiceType(CatalogCategory category,
                                           String nameUk,
                                           String nameEn,
                                           String slug,
                                           boolean active) {
        var type = ServiceType.builder()
                .category(category)
                .nameUk(nameUk)
                .nameEn(nameEn)
                .slug(slug)
                .active(active)
                .build();
        return em.persist(type);
    }

    // ── CatalogCategoryRepository ─────────────────────────────────────────────

    @Nested
    @DisplayName("CatalogCategoryRepository.findAllByOrderBySortOrderAsc")
    class FindAllByOrderBySortOrderAsc {

        @Test
        @DisplayName("should_returnCategoriesInAscendingOrder_when_insertedOutOfOrder")
        void should_returnCategoriesInAscendingOrder_when_insertedOutOfOrder() {
            persistCategory("Брови", "Brows", 3);
            persistCategory("Нігті", "Nails", 1);
            persistCategory("Вії",   "Eyelashes", 2);
            em.flush();

            List<CatalogCategory> results = categoryRepository.findAllByOrderBySortOrderAsc();

            assertThat(results)
                    .as("categories must be ordered by sort_order ascending")
                    .extracting(CatalogCategory::getSortOrder)
                    .containsExactly(1, 2, 3);
        }

        @Test
        @DisplayName("should_returnSingleElementList_when_onlyOneCategoryExists")
        void should_returnSingleElementList_when_onlyOneCategoryExists() {
            var category = persistCategory("Волосся", "Hair", 4);
            em.flush();

            List<CatalogCategory> results = categoryRepository.findAllByOrderBySortOrderAsc();

            assertThat(results).hasSize(1);
            assertThat(results.get(0).getId()).isEqualTo(category.getId());
        }

        @Test
        @DisplayName("should_returnEmptyList_when_noCategoriesExist")
        void should_returnEmptyList_when_noCategoriesExist() {
            List<CatalogCategory> results = categoryRepository.findAllByOrderBySortOrderAsc();

            assertThat(results).isEmpty();
        }
    }

    // ── ServiceTypeRepository — by category ───────────────────────────────────

    @Nested
    @DisplayName("ServiceTypeRepository.findAllByCategoryIdAndActiveTrueOrderByNameUkAsc")
    class FindAllByCategoryIdAndActiveTrueOrderByNameUkAsc {

        @Test
        @DisplayName("should_returnOnlyActiveTypes_when_categoryHasMixedActiveAndInactive")
        void should_returnOnlyActiveTypes_when_categoryHasMixedActiveAndInactive() {
            var nails = persistCategory("Нігті", "Nails", 1);
            persistServiceType(nails, "Гель-лак",          "Gel Polish",     "gel-polish",     true);
            persistServiceType(nails, "Класичний манікюр", "Classic Manicure","manicure-classic", true);
            persistServiceType(nails, "Застарілий тип",   "Deprecated",     "deprecated-nails", false);
            em.flush();

            List<ServiceType> results =
                    serviceTypeRepository.findAllByCategoryIdAndActiveTrueOrderByNameUkAsc(nails.getId());

            assertThat(results)
                    .as("only active service types must be returned")
                    .hasSize(2)
                    .extracting(ServiceType::isActive)
                    .containsOnly(true);
        }

        @Test
        @DisplayName("should_returnTypesInNameUkAscOrder_when_multipleActiveTypesExist")
        void should_returnTypesInNameUkAscOrder_when_multipleActiveTypesExist() {
            var nails = persistCategory("Нігті", "Nails", 1);
            // Insert deliberately out of alphabetical order
            persistServiceType(nails, "Педикюр",           "Pedicure",       "pedicure",       true);
            persistServiceType(nails, "Гель-лак",          "Gel Polish",     "gel-polish",     true);
            persistServiceType(nails, "Манікюр класичний", "Classic Manicure","manicure-cl",   true);
            em.flush();

            List<ServiceType> results =
                    serviceTypeRepository.findAllByCategoryIdAndActiveTrueOrderByNameUkAsc(nails.getId());

            assertThat(results)
                    .as("results must be ordered by name_uk ascending")
                    .extracting(ServiceType::getNameUk)
                    .isSortedAccordingTo(String::compareTo);
        }

        @Test
        @DisplayName("should_excludeTypesFromOtherCategory_when_queryingBySpecificCategoryId")
        void should_excludeTypesFromOtherCategory_when_queryingBySpecificCategoryId() {
            var nails  = persistCategory("Нігті",  "Nails",     1);
            var brows  = persistCategory("Брови",  "Brows",     2);
            persistServiceType(nails, "Гель-лак",       "Gel Polish",   "gel-polish-2",  true);
            persistServiceType(brows, "Корекція брів",  "Brow Shaping", "brow-shaping",  true);
            em.flush();

            List<ServiceType> results =
                    serviceTypeRepository.findAllByCategoryIdAndActiveTrueOrderByNameUkAsc(nails.getId());

            assertThat(results)
                    .as("types from a different category must not appear")
                    .hasSize(1)
                    .first()
                    .extracting(ServiceType::getNameUk)
                    .isEqualTo("Гель-лак");
        }

        @Test
        @DisplayName("should_returnEmptyList_when_categoryHasNoActiveTypes")
        void should_returnEmptyList_when_categoryHasNoActiveTypes() {
            var nails = persistCategory("Нігті", "Nails", 1);
            persistServiceType(nails, "Застарілий", "Deprecated", "dep-nails", false);
            em.flush();

            List<ServiceType> results =
                    serviceTypeRepository.findAllByCategoryIdAndActiveTrueOrderByNameUkAsc(nails.getId());

            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("should_returnEmptyList_when_categoryIdDoesNotExist")
        void should_returnEmptyList_when_categoryIdDoesNotExist() {
            List<ServiceType> results =
                    serviceTypeRepository.findAllByCategoryIdAndActiveTrueOrderByNameUkAsc(UUID.randomUUID());

            assertThat(results).isEmpty();
        }
    }

    // ── ServiceTypeRepository — all active ────────────────────────────────────

    @Nested
    @DisplayName("ServiceTypeRepository.findAllByActiveTrueOrderByNameUkAsc")
    class FindAllByActiveTrueOrderByNameUkAsc {

        @Test
        @DisplayName("should_returnOnlyActiveTypes_when_mixedActiveAndInactiveExist")
        void should_returnOnlyActiveTypes_when_mixedActiveAndInactiveExist() {
            var nails = persistCategory("Нігті", "Nails", 1);
            var brows = persistCategory("Брови", "Brows", 2);
            persistServiceType(nails, "Гель-лак",      "Gel Polish",   "gel-polish-3",  true);
            persistServiceType(brows, "Корекція брів", "Brow Shaping", "brow-shaping-2",true);
            persistServiceType(nails, "Застарілий",    "Deprecated",   "dep-2",         false);
            em.flush();

            List<ServiceType> results = serviceTypeRepository.findAllActiveOrderByNameUk(PageRequest.of(0, 200));

            assertThat(results)
                    .as("inactive service types must be excluded from the global list")
                    .hasSize(2)
                    .extracting(ServiceType::isActive)
                    .containsOnly(true);
        }

        @Test
        @DisplayName("should_returnTypesAcrossAllCategories_in_nameUkAscOrder")
        void should_returnTypesAcrossAllCategories_in_nameUkAscOrder() {
            var nails = persistCategory("Нігті", "Nails", 1);
            var brows = persistCategory("Брови", "Brows", 2);
            // Ukrainian alphabetical: Б < Г < М
            persistServiceType(nails, "Манікюр класичний", "Classic Manicure", "man-classic", true);
            persistServiceType(brows, "Брови ламінування", "Brow Lamination",  "brow-lam",   true);
            persistServiceType(nails, "Гель-лак",          "Gel Polish",       "gel-polish-4",true);
            em.flush();

            List<ServiceType> results = serviceTypeRepository.findAllActiveOrderByNameUk(PageRequest.of(0, 200));

            assertThat(results)
                    .as("all active types must be sorted by name_uk ascending regardless of category")
                    .extracting(ServiceType::getNameUk)
                    .isSortedAccordingTo(String::compareTo);
        }

        @Test
        @DisplayName("should_returnEmptyList_when_noActiveTypesExist")
        void should_returnEmptyList_when_noActiveTypesExist() {
            var nails = persistCategory("Нігті", "Nails", 1);
            persistServiceType(nails, "Застарілий", "Deprecated", "dep-3", false);
            em.flush();

            List<ServiceType> results = serviceTypeRepository.findAllActiveOrderByNameUk(PageRequest.of(0, 200));

            assertThat(results).isEmpty();
        }
    }

    // ── ServiceTypeRepository — pg_trgm search ────────────────────────────────

    @Nested
    @DisplayName("ServiceTypeRepository.searchByName (pg_trgm native query)")
    class SearchByName {

        @Test
        @DisplayName("should_returnActiveType_when_queryCloselyMatchesNameUk")
        void should_returnActiveType_when_queryCloselyMatchesNameUk() {
            var nails = persistCategory("Нігті", "Nails", 1);
            persistServiceType(nails, "Манікюр класичний", "Classic Manicure", "man-classic-2", true);
            em.flush();

            // "Манікюр" is a close match for "Манікюр класичний"
            List<ServiceType> results = serviceTypeRepository.searchByName("Манікюр", PageRequest.of(0, 20));

            assertThat(results)
                    .as("pg_trgm search must return the active type matching the query")
                    .isNotEmpty()
                    .extracting(ServiceType::getNameUk)
                    .contains("Манікюр класичний");
        }

        @Test
        @DisplayName("should_returnActiveType_when_queryCloselyMatchesNameEn")
        void should_returnActiveType_when_queryCloselyMatchesNameEn() {
            var nails = persistCategory("Нігті", "Nails", 1);
            persistServiceType(nails, "Гель-лак", "Gel Polish", "gel-polish-5", true);
            em.flush();

            List<ServiceType> results = serviceTypeRepository.searchByName("Gel Polish", PageRequest.of(0, 20));

            assertThat(results)
                    .as("pg_trgm search must match on name_en as well as name_uk")
                    .isNotEmpty()
                    .extracting(ServiceType::getNameUk)
                    .contains("Гель-лак");
        }

        @Test
        @DisplayName("should_excludeInactiveTypes_when_queryMatchesInactiveName")
        void should_excludeInactiveTypes_when_queryMatchesInactiveName() {
            var nails = persistCategory("Нігті", "Nails", 1);
            persistServiceType(nails, "Манікюр застарілий", "Deprecated Manicure", "man-dep", false);
            em.flush();

            List<ServiceType> results = serviceTypeRepository.searchByName("Манікюр застарілий", PageRequest.of(0, 20));

            assertThat(results)
                    .as("inactive service types must never appear in search results")
                    .isEmpty();
        }

        @Test
        @DisplayName("should_returnEmptyList_when_queryHasNoSimilarMatch")
        void should_returnEmptyList_when_queryHasNoSimilarMatch() {
            var nails = persistCategory("Нігті", "Nails", 1);
            persistServiceType(nails, "Манікюр класичний", "Classic Manicure", "man-classic-3", true);
            em.flush();

            // Completely unrelated term — similarity will be below 0.2 threshold
            List<ServiceType> results = serviceTypeRepository.searchByName("xyzxyzxyz", PageRequest.of(0, 20));

            assertThat(results)
                    .as("query with no similar match must return empty list")
                    .isEmpty();
        }
    }

    // ── ServiceTypeRepository — findAllActiveWithCategory (JOIN FETCH) ────────

    @Nested
    @DisplayName("ServiceTypeRepository.findAllActiveWithCategory")
    class FindAllActiveWithCategory {

        @Test
        @DisplayName("should_returnActiveTypesWithCategoryInitialised_when_mixedActiveAndInactiveExist")
        void should_returnActiveTypesWithCategoryInitialised_when_mixedActiveAndInactiveExist() {
            var nails = persistCategory("Нігті", "Nails", 1);
            var brows = persistCategory("Брови", "Brows", 2);
            persistServiceType(nails, "Гель-лак",      "Gel Polish",   "gel-polish-fw1",  true);
            persistServiceType(brows, "Корекція брів", "Brow Shaping", "brow-shaping-fw1",true);
            persistServiceType(nails, "Застарілий",    "Deprecated",   "dep-fw1",         false);
            em.flush();

            List<ServiceType> results = serviceTypeRepository.findAllActiveWithCategory();

            assertThat(results)
                    .as("only active service types must be returned")
                    .hasSize(2)
                    .extracting(ServiceType::isActive)
                    .containsOnly(true);

            // Category must be initialised by JOIN FETCH — accessing it must not throw LazyInitializationException
            assertThat(results)
                    .as("JOIN FETCH must initialise the category association on every result")
                    .extracting(t -> t.getCategory().getNameUk())
                    .containsExactlyInAnyOrder("Нігті", "Брови");
        }

        @Test
        @DisplayName("should_returnResultsOrderedByNameUkAsc_when_multipleActiveTypesExist")
        void should_returnResultsOrderedByNameUkAsc_when_multipleActiveTypesExist() {
            var nails = persistCategory("Нігті", "Nails", 1);
            persistServiceType(nails, "Педикюр класичний", "Classic Pedicure", "pedicure-fw1", true);
            persistServiceType(nails, "Гель-лак",          "Gel Polish",       "gel-polish-fw2",true);
            persistServiceType(nails, "Манікюр класичний", "Classic Manicure", "man-classic-fw1",true);
            em.flush();

            List<ServiceType> results = serviceTypeRepository.findAllActiveWithCategory();

            assertThat(results)
                    .as("results must be ordered by name_uk ascending")
                    .extracting(ServiceType::getNameUk)
                    .isSortedAccordingTo(String::compareTo);
        }

        @Test
        @DisplayName("should_returnEmptyList_when_noActiveTypesExist")
        void should_returnEmptyList_when_noActiveTypesExist() {
            var nails = persistCategory("Нігті", "Nails", 1);
            persistServiceType(nails, "Застарілий", "Deprecated", "dep-fw2", false);
            em.flush();

            List<ServiceType> results = serviceTypeRepository.findAllActiveWithCategory();

            assertThat(results).as("no active types must yield an empty list").isEmpty();
        }
    }

    // ── ServiceTypeRepository — findByCategoryWithCategory (JOIN FETCH) ───────

    @Nested
    @DisplayName("ServiceTypeRepository.findByCategoryWithCategory")
    class FindByCategoryWithCategory {

        @Test
        @DisplayName("should_returnOnlyActiveTypesForCategory_with_categoryInitialised")
        void should_returnOnlyActiveTypesForCategory_with_categoryInitialised() {
            var nails = persistCategory("Нігті", "Nails", 1);
            var brows = persistCategory("Брови", "Brows", 2);
            persistServiceType(nails, "Гель-лак",      "Gel Polish",   "gel-polish-bcc1", true);
            persistServiceType(nails, "Застарілий",    "Deprecated",   "dep-bcc1",        false);
            persistServiceType(brows, "Корекція брів", "Brow Shaping", "brow-bcc1",       true);
            em.flush();

            List<ServiceType> results = serviceTypeRepository.findByCategoryWithCategory(nails.getId());

            assertThat(results)
                    .as("only active types from the requested category must be returned")
                    .hasSize(1);

            ServiceType result = results.get(0);
            assertThat(result.getNameUk())
                    .as("returned type must be the active nails type")
                    .isEqualTo("Гель-лак");

            // Category must be initialised by JOIN FETCH
            assertThat(result.getCategory().getId())
                    .as("JOIN FETCH must initialise the category — id must match the queried category")
                    .isEqualTo(nails.getId());
            assertThat(result.getCategory().getNameUk())
                    .as("JOIN FETCH must initialise the category — nameUk must be readable without a second query")
                    .isEqualTo("Нігті");
        }

        @Test
        @DisplayName("should_returnTypesOrderedByNameUkAsc_when_multipleActiveTypesInCategory")
        void should_returnTypesOrderedByNameUkAsc_when_multipleActiveTypesInCategory() {
            var nails = persistCategory("Нігті", "Nails", 1);
            persistServiceType(nails, "Педикюр",           "Pedicure",       "pedicure-bcc1",  true);
            persistServiceType(nails, "Гель-лак",          "Gel Polish",     "gel-polish-bcc2", true);
            persistServiceType(nails, "Манікюр класичний", "Classic Manicure","man-classic-bcc1",true);
            em.flush();

            List<ServiceType> results = serviceTypeRepository.findByCategoryWithCategory(nails.getId());

            assertThat(results)
                    .as("results must be ordered by name_uk ascending")
                    .extracting(ServiceType::getNameUk)
                    .isSortedAccordingTo(String::compareTo);
        }

        @Test
        @DisplayName("should_excludeTypesFromOtherCategory_when_queryingBySpecificCategoryId")
        void should_excludeTypesFromOtherCategory_when_queryingBySpecificCategoryId() {
            var nails = persistCategory("Нігті", "Nails", 1);
            var brows = persistCategory("Брови", "Brows", 2);
            persistServiceType(nails, "Гель-лак",      "Gel Polish",   "gel-polish-bcc3", true);
            persistServiceType(brows, "Корекція брів", "Brow Shaping", "brow-bcc2",       true);
            em.flush();

            List<ServiceType> results = serviceTypeRepository.findByCategoryWithCategory(nails.getId());

            assertThat(results)
                    .as("types from a different category must not appear in category-filtered results")
                    .hasSize(1)
                    .first()
                    .extracting(ServiceType::getNameUk)
                    .isEqualTo("Гель-лак");
        }

        @Test
        @DisplayName("should_returnEmptyList_when_categoryIdDoesNotExist")
        void should_returnEmptyList_when_categoryIdDoesNotExist() {
            List<ServiceType> results = serviceTypeRepository.findByCategoryWithCategory(UUID.randomUUID());

            assertThat(results)
                    .as("non-existent category id must yield an empty list")
                    .isEmpty();
        }
    }

    // ── ServiceType entity — slug uniqueness constraint ───────────────────────

    @Nested
    @DisplayName("ServiceType.slug uniqueness constraint")
    class SlugUniqueness {

        @Test
        @DisplayName("should_throwException_when_duplicateSlugIsInserted")
        void should_throwException_when_duplicateSlugIsInserted() {
            var nails = persistCategory("Нігті", "Nails", 1);
            persistServiceType(nails, "Гель-лак", "Gel Polish", "duplicate-slug", true);
            em.flush();

            var duplicate = ServiceType.builder()
                    .category(nails)
                    .nameUk("Інший тип")
                    .nameEn("Other Type")
                    .slug("duplicate-slug")
                    .active(true)
                    .build();
            em.persist(duplicate);

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> em.flush())
                    .as("inserting a ServiceType with a duplicate slug must violate the unique constraint")
                    .isInstanceOf(Exception.class)
                    .satisfies(ex -> assertThat(ex.getMessage() != null || ex.getCause() != null)
                            .as("exception or its cause must carry constraint violation information")
                            .isTrue());
        }
    }
}
