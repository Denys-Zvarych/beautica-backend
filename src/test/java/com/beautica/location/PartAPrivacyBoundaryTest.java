package com.beautica.location;

import com.beautica.salon.dto.PublicSalonResponse;
import com.beautica.salon.dto.SalonResponse;
import com.beautica.search.dto.MasterSearchResult;
import com.beautica.search.dto.SalonSearchResult;
import com.beautica.user.UserProfileResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 10.7 AC4 — privacy-boundary regression guard.
 *
 * <p>Part A exposes only district/city granularity + free-text
 * {@code location_note} + the legacy {@code Salon.address} string + phone/IG.
 * There is deliberately <b>no lat/lng, no exact-point, no jitter</b> — those
 * are Part B and must not be partially implemented here.
 *
 * <p>This test reflects over every response DTO that participates in a Part A
 * discovery or profile flow and fails if any field name hints at a
 * precise-location coordinate. A static reflective assertion (rather than a
 * booted slice) is the right tool: it pins the contract at zero infra cost and
 * trips the moment a Part B field is added to a Part A DTO by mistake (§M —
 * slice/no-boot over full {@code @SpringBootTest}).
 */
@DisplayName("Phase 10.7 — Part A privacy boundary (no precise-location field)")
class PartAPrivacyBoundaryTest {

    /**
     * Exact camelCase <em>word segments</em> that mark a precise-location
     * (Part B) field — lat/lng/exact-point/jitter/radius/bbox/geo. Matched
     * against whole segments only (the name is split on camelCase / digit
     * boundaries and lowercased), never as a raw substring: a plain
     * {@code contains} check would false-positive on {@code salonId}
     * ({@code "lon"}) and {@code cityLabel}, which are legitimate Part A
     * fields. {@code locationNote} splits to {@code ["location","note"]} — no
     * segment is a coordinate token, so the landmark field is correctly
     * allowed.
     */
    private static final Set<String> FORBIDDEN_COORDINATE_SEGMENTS = Set.of(
            "lat", "lng", "latitude", "longitude",
            "coordinate", "coordinates", "geo", "geom", "geometry",
            "point", "jitter", "radius", "bbox");

    static Stream<Class<?>> partAResponseDtos() {
        return Stream.of(
                MasterSearchResult.class,
                SalonSearchResult.class,
                PublicSalonResponse.class,
                SalonResponse.class,
                UserProfileResponse.class);
    }

    @ParameterizedTest(name = "{0} carries no precise-location field")
    @MethodSource("partAResponseDtos")
    @DisplayName("no Part A response DTO exposes a lat/lng/exact-point field")
    void should_notExposePreciseLocationField_when_partADto(Class<?> dto) {
        assertThat(dto.isRecord())
                .as("%s must be a record DTO", dto.getSimpleName())
                .isTrue();

        List<String> offending = Arrays.stream(dto.getRecordComponents())
                .map(RecordComponent::getName)
                .filter(PartAPrivacyBoundaryTest::looksLikeCoordinate)
                .toList();

        assertThat(offending)
                .as("%s must not expose any precise-location (Part B) field — "
                        + "Part A is district/city granularity only", dto.getSimpleName())
                .isEmpty();
    }

    /**
     * Splits a record-component name into lowercase camelCase / digit-boundary
     * word segments and flags it if any whole segment is a known
     * precise-location token. Segment matching (not substring) is what keeps
     * {@code salonId} → {@code ["salon","id"]} and {@code cityLabel} →
     * {@code ["city","label"]} out of the false-positive set.
     */
    private static boolean looksLikeCoordinate(String componentName) {
        String[] segments = componentName.split("(?<=[a-z])(?=[A-Z])|(?<=[A-Za-z])(?=[0-9])|(?<=[0-9])(?=[A-Za-z])");
        return Arrays.stream(segments)
                .map(segment -> segment.toLowerCase(Locale.ROOT))
                .anyMatch(FORBIDDEN_COORDINATE_SEGMENTS::contains);
    }
}
