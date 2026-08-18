package com.beautica.service.service;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.Cacheable;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 253 audit-fix (cycle 1) — THE load-bearing architectural guard for the
 * {@code masterServices} cache-leak fix. The whole safety of Phase 252/253 rests on one
 * property: no {@code @Cacheable(value = "masterServices", ...)} method may ever call
 * {@link MasterServiceFavoriteDecorator}, and the rejected {@code getMasterServicesForCaller(...)}
 * self-invocation shape (a wrapper INSIDE {@link ServiceCatalogService} that decorates before
 * returning) must never come back. Before this test, that guarantee rested only on a javadoc
 * note ({@link MasterServiceFavoriteDecorator}'s class comment) and code review —
 * {@code ServiceCatalogFavoriteCacheIT} only pins the ONE method that exists today; it says
 * nothing about a second cached method a future dev might add.
 *
 * <p>This test asserts BOTH halves of the property mechanically:
 * <ol>
 *   <li>Exactly one method anywhere in {@code src/main} carries a {@code @Cacheable} whose cache
 *       name set includes {@code "masterServices"}, and it is
 *       {@link ServiceCatalogService#getMasterServices(java.util.UUID)}.</li>
 *   <li>{@link ServiceCatalogService} has no dependency — field, call, import, or otherwise — on
 *       {@link MasterServiceFavoriteDecorator} at all.</li>
 * </ol>
 *
 * <p>Together they close the gap: a dev cannot add a second {@code @Cacheable("masterServices")}
 * method (rule 1 fails), and cannot make {@code getMasterServices} itself call the decorator or
 * grow a decorator-calling sibling inside the same class (rule 2 fails the moment the class
 * references the decorator type at all — the correct fix is always to compose the decorator in
 * the controller, exactly as {@code ServiceController} does today).
 *
 * <p>Mirrors {@code ClosureReminderArchitectureTest}'s use of ArchUnit (already a test
 * dependency — {@code archunit-junit5}) rather than a hand-rolled reflection scan, for the same
 * reason: mechanical enforcement of an architectural invariant should not depend on every future
 * reviewer remembering to re-derive it from a javadoc note.
 */
@DisplayName("ServiceCatalogServiceArchitectureTest — the masterServices cache-leak fix cannot be silently reintroduced")
class ServiceCatalogServiceArchitectureTest {

    private static final String BASE_PACKAGE = "com.beautica";
    private static final String MASTER_SERVICES_CACHE = "masterServices";

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE_PACKAGE);
    }

    @Test
    @DisplayName("exactly one @Cacheable(\"masterServices\") method exists in src/main, and it is "
            + "ServiceCatalogService#getMasterServices")
    void should_haveExactlyOneMasterServicesCacheableMethod_andNeverCallTheFavoriteDecorator() {
        List<JavaMethod> cacheableOnMasterServices = classes.stream()
                .flatMap(c -> c.getMethods().stream())
                .filter(m -> m.isAnnotatedWith(Cacheable.class))
                .filter(m -> targetsMasterServicesCache(m.getAnnotationOfType(Cacheable.class)))
                .toList();

        assertThat(cacheableOnMasterServices)
                .as("every @Cacheable method in src/main whose cache-name set includes "
                        + "\"masterServices\" — there must be exactly one, or a second cached read "
                        + "path could decorate with per-client data and leak it across clients/guests "
                        + "for the cache's TTL")
                .hasSize(1);

        JavaMethod theOne = cacheableOnMasterServices.get(0);
        assertThat(theOne.getOwner().isEquivalentTo(ServiceCatalogService.class))
                .as("the sole masterServices-cached method must be declared on ServiceCatalogService, "
                        + "found on %s instead", theOne.getOwner().getFullName())
                .isTrue();
        assertThat(theOne.getName()).isEqualTo("getMasterServices");
    }

    @Test
    @DisplayName("ServiceCatalogService never references MasterServiceFavoriteDecorator — the decorator "
            + "must stay composed in the controller, lexically outside any @Cacheable method")
    void should_neverDependOnFavoriteDecorator() {
        ArchRule rule = noClasses()
                .that().haveFullyQualifiedName(ServiceCatalogService.class.getName())
                .should().dependOnClassesThat().haveFullyQualifiedName(MasterServiceFavoriteDecorator.class.getName())
                .because("MasterServiceFavoriteDecorator must only ever be invoked from OUTSIDE a "
                        + "@Cacheable(\"masterServices\") method (controller-level composition, see "
                        + "its class javadoc); any reference from ServiceCatalogService itself is the "
                        + "rejected getMasterServicesForCaller(...) self-invocation shape that would "
                        + "store one client's wish list under the shared masterId cache key");

        rule.check(classes);
    }

    private static boolean targetsMasterServicesCache(Cacheable annotation) {
        Set<String> names = new HashSet<>();
        names.addAll(Arrays.asList(annotation.value()));
        names.addAll(Arrays.asList(annotation.cacheNames()));
        return names.contains(MASTER_SERVICES_CACHE);
    }
}
