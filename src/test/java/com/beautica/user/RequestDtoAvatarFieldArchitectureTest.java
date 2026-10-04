package com.beautica.user;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 344 D2, widened — a personal avatar is written ONLY by {@code POST/DELETE
 * /api/v1/media/avatar}, which take the target from the JWT and accept the image as a multipart
 * part. No JSON request DTO anywhere under {@code com.beautica} may therefore carry an avatar
 * property: one would open a second, possibly cross-user, write path to {@code users.avatar_url}.
 *
 * <p>The earlier pin in {@code UserControllerIT} TC-4 reflected over {@link UpdateProfileRequest}
 * only. This test scans every production class whose simple name ends in {@code Request} (records
 * and classes, top-level and nested) and fails on any bindable property name containing
 * {@code avatar} (case-insensitive), as extracted by {@link RequestPropertyNames}: record components,
 * fields, {@code @JsonCreator} parameters and setters, on the type and every superclass, plus their
 * Jackson {@code @JsonProperty}/{@code @JsonSetter}/{@code @JsonAlias} names — which catches
 * {@code @JsonProperty("avatarUrl") String pic}. Positive controls in {@link AvatarPropertyFixtures}
 * (test sources only) prove the extraction flags each route.
 *
 * <p>Allow-list: empty. A grep on 2026-10-04 found no avatar-named request property; salon logo /
 * cover and service photos are multipart uploads, not JSON fields. Adding an entry requires a
 * security sign-off that the new path is self-only.
 */
@DisplayName("Request DTOs — no *Request type under com.beautica exposes an avatar property (Phase 344 D2)")
class RequestDtoAvatarFieldArchitectureTest {

    private static final String BASE_PACKAGE = "com.beautica";

    /** Fully-qualified "Type#property" entries that are legitimately avatar-named. Deliberately empty. */
    private static final Set<String> ALLOW_LIST = Set.of();

    private static List<Class<?>> requestTypes;

    @BeforeAll
    static void importRequestTypes() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
                .importPackages(BASE_PACKAGE);
        requestTypes = classes.stream()
                .filter(c -> c.getSimpleName().endsWith("Request"))
                .filter(c -> !c.isInterface() && !c.isAnnotation() && !c.isEnum())
                .map(JavaClass::reflect)
                .toList();
    }

    @Test
    @DisplayName("the scan is not vacuous — it finds many *Request types, including UpdateProfileRequest")
    void should_findRequestTypes_when_productionClassesScanned() {
        assertThat(requestTypes)
                .as("an empty/near-empty scan would make the avatar rule pass for the wrong reason")
                .hasSizeGreaterThan(20)
                .contains(UpdateProfileRequest.class);
    }

    @Test
    @DisplayName("no *Request record component, field or JSON name contains 'avatar' — /media/avatar is the only writer")
    void should_haveNoAvatarProperty_when_anyRequestTypeScanned() {
        List<String> offenders = new ArrayList<>();
        for (Class<?> type : requestTypes) {
            RequestPropertyNames.avatarProperties(type).stream()
                    .filter(entry -> !ALLOW_LIST.contains(entry))
                    .forEach(offenders::add);
        }

        assertThat(offenders)
                .as("request DTOs must not carry an avatar property (Phase 344 D2): the personal "
                        + "avatar is self-only via POST/DELETE /api/v1/media/avatar. Offenders=%s", offenders)
                .isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(classes = {
            AvatarPropertyFixtures.PlantedAvatarRequest.class,
            AvatarPropertyFixtures.PlainAvatarRequest.class,
            AvatarPropertyFixtures.InheritedAvatarRequest.class,
            AvatarPropertyFixtures.CreatorAvatarRequest.class,
            AvatarPropertyFixtures.SetterAvatarRequest.class})
    @DisplayName("positive control — the same extraction flags a planted avatar property on every binding route")
    void should_flagAvatarProperty_when_plantedFixtureScanned(Class<?> planted) {

        List<String> flagged = RequestPropertyNames.avatarProperties(planted);

        assertThat(flagged)
                .as("the scan must catch an avatar property bound via %s, or the production pass is vacuous",
                        planted.getSimpleName())
                .contains(planted.getName() + "#avatarUrl");
    }

    @Test
    @DisplayName("negative control — a request type with no avatar-named binding is not flagged")
    void should_notFlag_when_noAvatarPropertyPresent() {

        List<String> flagged = RequestPropertyNames.avatarProperties(AvatarPropertyFixtures.CleanRequest.class);

        assertThat(flagged).isEmpty();
        assertThat(RequestPropertyNames.of(AvatarPropertyFixtures.CleanRequest.class))
                .contains("firstName", "lastName", "last_name");
    }
}
