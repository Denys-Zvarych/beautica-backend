package com.beautica.search.repository;

import com.beautica.salon.repository.SalonSearchSql;
import com.beautica.search.service.SearchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the SQL-injection invariant behind {@code SearchSuggestionAvailabilityRepository}'s
 * {@code %s}-templated query (audit-fix cycle 1, finding 1 — LOW security): the assembled SQL text
 * for a given {@link SearchSuggestionAvailabilityRepository.LocalityMode} is IDENTICAL byte for byte
 * on every call, built ONLY from fixed literals/enum constants — never from a caller-supplied
 * {@code String}. No Spring context, no DB —
 * {@link SearchSuggestionAvailabilityRepository#buildAvailabilitySql} is a pure function of the mode.
 *
 * <p>Independently reconstructs the expected text from the SAME shared primitives the repository
 * itself pulls from ({@link SearchService}'s public constants/methods, {@link SalonSearchSql}'s
 * public predicates) via a SEPARATE code path, so a mutation inside the repository that swaps a
 * fixed fragment for one built from a request value diverges from this independent reconstruction
 * and fails.
 *
 * <p><b>Falsification (recorded in the audit-fix report, not re-run here):</b> manually mutating
 * {@code MasterLocalityFragment.CITY} to splice a literal UUID instead of the {@code :cityId}
 * placeholder made {@code should_matchKnownShape_forEveryLocalityMode} fail (text diverges from the
 * independently-rebuilt expected string) AND made
 * {@code should_containOnlyKnownPlaceholders_andNoInlinedUuid} fail independently (the UUID-literal
 * regex tripped) — two independent guards, both confirmed red under the mutation, then reverted.
 */
@DisplayName("SearchSuggestionAvailabilityRepository — SQL-shape invariant (finding 1)")
class SearchSuggestionAvailabilityRepositorySqlShapeTest {

    // (?<!:) — a Postgres `::date` cast (the Kyiv-date spelling MasterBookabilitySql emits) is not a
    // bind parameter; Hibernate treats `::` as an escaped cast, so must this extraction.
    private static final Pattern NAMED_PARAM = Pattern.compile("(?<!:):([A-Za-z][A-Za-z0-9]*)");
    private static final Pattern UUID_LITERAL =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private static String masterActivePredicate() {
        StringBuilder sb = new StringBuilder();
        SearchService.appendMasterActiveIndependentPredicate(sb, new HashMap<>());
        return sb.toString();
    }

    private static String salonBookableGate() {
        StringBuilder sb = new StringBuilder();
        SearchService.appendSalonBookableGate(sb, "sd", "s");
        return sb.toString();
    }

    private static String masterLocality(SearchSuggestionAvailabilityRepository.LocalityMode mode) {
        return switch (mode) {
            case NATIONAL -> "";
            case CITY -> "AND " + SearchService.DISCOVERY_CITY_EXPR + " = :cityId ";
            case DISTRICT -> "AND " + SearchService.DISCOVERY_DISTRICT_EXPR + " = :districtId ";
        };
    }

    private static String salonLocality(SearchSuggestionAvailabilityRepository.LocalityMode mode) {
        return switch (mode) {
            case NATIONAL -> "";
            case CITY -> SalonSearchSql.STATIC_CITY_PREDICATE;
            case DISTRICT -> SalonSearchSql.STATIC_DISTRICT_PREDICATE;
        };
    }

    /** Independently rebuilds the expected SQL text from the same shared primitives, byte for byte. */
    private static String expectedSql(SearchSuggestionAvailabilityRepository.LocalityMode mode) {
        return "SELECT DISTINCT sd.category AS category, sd.service_type_id AS service_type_id "
                + "FROM masters m "
                + "JOIN users u ON u.id = m.user_id "
                + "LEFT JOIN salons sal ON sal.id = m.salon_id "
                + "JOIN master_services ms ON ms.master_id = m.id AND ms.is_active = true "
                + "JOIN service_definitions sd ON sd.id = ms.service_def_id AND sd.is_active = true "
                + "WHERE " + masterActivePredicate()
                + masterLocality(mode)
                + "UNION "
                + "SELECT DISTINCT sd.category AS category, sd.service_type_id AS service_type_id "
                + "FROM salons s "
                + "JOIN service_definitions sd ON sd.owner_type = 'SALON' AND sd.owner_id = s.id "
                + "AND sd.is_active = true "
                + "WHERE s.is_active = true "
                + salonLocality(mode)
                + salonBookableGate();
    }

    @ParameterizedTest
    @EnumSource(SearchSuggestionAvailabilityRepository.LocalityMode.class)
    @DisplayName("assembled SQL for each mode matches the independently-rebuilt expected text exactly")
    void should_matchKnownShape_forEveryLocalityMode(SearchSuggestionAvailabilityRepository.LocalityMode mode) {
        String actual = SearchSuggestionAvailabilityRepository.buildAvailabilitySql(mode);

        assertThat(actual).isEqualTo(expectedSql(mode));
    }

    @ParameterizedTest
    @EnumSource(SearchSuggestionAvailabilityRepository.LocalityMode.class)
    @DisplayName("assembled SQL is IDENTICAL across repeated calls for the same mode (no per-call variance)")
    void should_beIdentical_acrossRepeatedCallsForTheSameMode(SearchSuggestionAvailabilityRepository.LocalityMode mode) {
        String first = SearchSuggestionAvailabilityRepository.buildAvailabilitySql(mode);
        String second = SearchSuggestionAvailabilityRepository.buildAvailabilitySql(mode);

        assertThat(first).isEqualTo(second);
    }

    @ParameterizedTest
    @EnumSource(SearchSuggestionAvailabilityRepository.LocalityMode.class)
    @DisplayName("only the known named parameters appear — no other placeholder, no inlined UUID literal")
    void should_containOnlyKnownPlaceholders_andNoInlinedUuid(SearchSuggestionAvailabilityRepository.LocalityMode mode) {
        String sql = SearchSuggestionAvailabilityRepository.buildAvailabilitySql(mode);

        Matcher paramMatcher = NAMED_PARAM.matcher(sql);
        Set<String> foundParams = new HashSet<>();
        while (paramMatcher.find()) {
            foundParams.add(paramMatcher.group(1));
        }
        // bookableToday: the app-clock Kyiv date every bookability fragment reads (audit 2026-10-05,
        // finding 7) — bound by the repository from ScheduleDateMath, never caller-supplied.
        String today = com.beautica.master.repository.MasterBookabilitySql.TODAY_PARAM;
        Set<String> allowed = switch (mode) {
            case NATIONAL -> Set.of("includedRole", today);
            case CITY -> Set.of("includedRole", "cityId", today);
            case DISTRICT -> Set.of("includedRole", "districtId", today);
        };

        assertThat(foundParams)
                .as("every :param in the SQL text must be one of the known bound names")
                .isSubsetOf(allowed);
        assertThat(UUID_LITERAL.matcher(sql).find())
                .as("no caller-supplied id may ever be spliced into the SQL text as a literal")
                .isFalse();
    }
}
