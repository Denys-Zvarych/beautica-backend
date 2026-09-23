package com.beautica.location.service;

import com.beautica.location.dto.SettlementSearchResponse;
import com.beautica.location.entity.SettlementType;
import com.beautica.location.repository.CityRepository;
import com.beautica.location.repository.CityRepository.SettlementSearchRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SettlementSearchService} — the three-state routing, the normalisation of
 * the caller's term, and the exact values bound into the repository query.
 *
 * <p>The repository is mocked, so this class deliberately proves nothing about ranking: what
 * «льв» actually returns depends on 25 698 real rows and a real GIN index, and is pinned by
 * {@code SettlementSearchIT} against the migrated database. What CAN only be proved here is the
 * argument contract — that a below-minimum term never reaches the database at all, that the LIKE
 * pattern is escaped and apostrophe-folded before binding, and that the cap and similarity floor
 * are passed rather than assumed.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SettlementSearchService — routing, normalisation and query binding")
class SettlementSearchServiceTest {

    @Mock
    private CityRepository cityRepository;

    private SettlementSearchService service;

    @BeforeEach
    void setUp() {
        service = new SettlementSearchService(cityRepository);

        // The blank-query branch calls listMajorSettlements() through the @Lazy self-proxy so the
        // @Cacheable is honoured (§F-3). Outside Spring there is no proxy, so `self` is wired to
        // the instance itself — the routing is what is under test here, not the caching, which
        // SettlementSearchIT asserts against the live CacheManager.
        ReflectionTestUtils.setField(service, "self", service);
    }

    private static SettlementSearchRow row(String nameUk, String type, String oblast) {
        UUID id = UUID.randomUUID();
        return new SettlementSearchRow() {
            @Override public UUID getSettlementId() {
                return id;
            }

            @Override public String getNameUk() {
                return nameUk;
            }

            @Override public String getSettlementType() {
                return type;
            }

            @Override public String getOblastNameUk() {
                return oblast;
            }
        };
    }

    @Nested
    @DisplayName("blank query — the pre-typing state")
    class BlankQuery {

        @Test
        @DisplayName("null query returns the major settlements, never a search")
        void should_returnMajorSettlements_when_queryIsNull() {
            when(cityRepository.findMajorSettlements())
                    .thenReturn(List.of(row("Київ", "CITY", "Київ")));

            List<SettlementSearchResponse> result = service.search(null);

            assertThat(result).extracting(SettlementSearchResponse::nameUk).containsExactly("Київ");
            verify(cityRepository, never())
                    .searchByName(anyString(), anyString(), anyDouble(), anyInt());
        }

        @Test
        @DisplayName("whitespace-only query is the pre-typing state, not a too-short one")
        void should_returnMajorSettlements_when_queryIsWhitespaceOnly() {
            when(cityRepository.findMajorSettlements())
                    .thenReturn(List.of(row("Львів", "CITY", "Львівська")));

            List<SettlementSearchResponse> result = service.search("   ");

            assertThat(result).hasSize(1);
            verify(cityRepository, never())
                    .searchByName(anyString(), anyString(), anyDouble(), anyInt());
        }
    }

    @Nested
    @DisplayName("below the minimum length")
    class BelowMinimum {

        @Test
        @DisplayName("a 2-character query returns empty and issues NO database query")
        void should_returnEmptyWithoutQuerying_when_termIsTwoCharacters() {
            List<SettlementSearchResponse> result = service.search("ль");

            assertThat(result).isEmpty();
            verifyNoInteractions(cityRepository);
        }

        @Test
        @DisplayName("a 2-character query padded with whitespace is still too short")
        void should_returnEmptyWithoutQuerying_when_paddedTermIsTwoCharacters() {
            List<SettlementSearchResponse> result = service.search("  ль  ");

            assertThat(result).isEmpty();
            verifyNoInteractions(cityRepository);
        }

        @Test
        @DisplayName("isNotIndexServable separates 'typed but short' from 'not typed'")
        void should_distinguishBlankFromTooShort_when_classifyingRawQueries() {
            assertThat(SettlementSearchService.isNotIndexServable(null)).isFalse();
            assertThat(SettlementSearchService.isNotIndexServable("")).isFalse();
            assertThat(SettlementSearchService.isNotIndexServable("   ")).isFalse();

            assertThat(SettlementSearchService.isNotIndexServable("л")).isTrue();
            assertThat(SettlementSearchService.isNotIndexServable("ль")).isTrue();

            assertThat(SettlementSearchService.isNotIndexServable("льв")).isFalse();
        }

        @Test
        @DisplayName("spaced single letters are unservable — the floor is per TOKEN, not per term")
        void should_treatSpacedSingleLettersAsUnservable_when_noTokenReachesTheFloor() {
            // REVISED with the Phase 326 per-token fix, and the revision is the finding: this
            // assertion previously read isFalse() — "а   б" normalises to the 3-character "а б",
            // so a whole-term length floor called it servable and executed it. It is not. Neither
            // token reaches a full 3-gram, so pg_trgm extracts only the padded keys of two
            // single-letter words, and a 0.3 threshold over so few distinct keys makes almost the
            // whole table a candidate. This is «ка »x17 at n=2, and the old expectation pinned the
            // defect in place: any fix to the DoS vector had to turn this line red, which is
            // exactly what it did.
            assertThat(SettlementSearchService.isNotIndexServable("а   б")).isTrue();
            assertThat(SettlementSearchService.isNotIndexServable("а    ")).isTrue();
            // The controls: one token that DOES reach the floor makes the term servable again,
            // whatever unservable fragments sit beside it.
            assertThat(SettlementSearchService.isNotIndexServable("а   львів")).isFalse();
        }
    }

    /**
     * The zero-trigram guard — the state the length floor does NOT cover.
     *
     * <p>{@code pg_trgm} extracts trigrams from alphanumeric runs only, so a term made entirely of
     * punctuation produces an empty key set, the planner abandons {@code idx_cities_name_uk_trgm}
     * and both tiers become a sequential scan of 25 697 rows calling {@code similarity()} on each
     * — 119 ms per request against 3.4 ms for «нов», on a {@code permitAll} endpoint whose per-IP
     * budget is 240 requests a minute. The only place this can be stopped is before the call, which
     * is what these tests pin: the assertion is {@code verifyNoInteractions}, not a returned value,
     * because an empty list is equally consistent with "never asked" and with "scanned everything
     * and matched nothing".
     */
    @Nested
    @DisplayName("zero-trigram terms — long enough to pass the floor, unservable by the index")
    class ZeroTrigramTerms {

        @Test
        @DisplayName("100 bullets pass every length and character check and still never reach the DB")
        void should_returnEmptyWithoutQuerying_when_termHasNoAlphanumericCharacter() {
            List<SettlementSearchResponse> result = service.search("•".repeat(100));

            assertThat(result).isEmpty();
            verifyNoInteractions(cityRepository);
        }

        @Test
        @DisplayName("every punctuation class measured at 115-119 ms is rejected, not just the bullet")
        void should_returnEmptyWithoutQuerying_when_termIsAnyPunctuationRun() {
            // All four were measured as Seq Scan / Rows Removed by Filter: 25697. The apostrophe
            // run is the worst of them: SettlementSearchService folds U+0027 onto U+2019, so 100
            // typed apostrophes become 300 bytes before the term is ever bound.
            assertThat(service.search("%".repeat(100))).isEmpty();
            assertThat(service.search(".".repeat(100))).isEmpty();
            assertThat(service.search("'".repeat(100))).isEmpty();
            assertThat(service.search("___")).isEmpty();

            verifyNoInteractions(cityRepository);
        }

        @Test
        @DisplayName("ONE alphanumeric is no longer enough — the run must reach the trigram floor")
        void should_returnEmptyWithoutQuerying_when_theTermsOnlyAlphanumericRunIsOneCharacter() {
            // REVISED. This test previously asserted the OPPOSITE — that «•»x99 + «о» was served,
            // on the strength of a 5.9 ms measurement — and its comment warned against tightening
            // the guard because that "would silently start rejecting «Кам’янка»-shaped input".
            // The warning was right about ratios and wrong about runs: re-measured on the real
            // table, «Кам’янка» (runs «Кам»/«янка»), «Івано-Франківськ», «с. Нове» and «112» all
            // carry a 3-character alphanumeric run and are unaffected, and exactly two of the
            // 25 697 settlements have none at all — the two villages named «Яр», already
            // unreachable under the 3-character floor that predates this.
            //
            // What the presence rule did admit was «•к»x25: one 50-character token, alphanumeric
            // throughout, every run a single letter, two distinct trigram keys, 3 022 similarity()
            // rechecks and 21.7 ms — twice the worst benign query. Presence was the third proxy to
            // be defeated by padding, and the run is the first that cannot be.
            assertThat(service.search("•".repeat(99) + "о")).isEmpty();
            assertThat(service.search("•к".repeat(25))).isEmpty();

            verifyNoInteractions(cityRepository);
        }

        @Test
        @DisplayName("a run of exactly 3 is served — the boundary in the ALLOWING direction")
        void should_queryTheRepository_when_aRunReachesExactlyTheFloor() {
            // Without this, tightening the run to 4 would leave every rejection assertion green
            // while «нов», «Яр»-adjacent prefixes and every 3-letter keystroke stopped working.
            when(cityRepository.searchByName(anyString(), anyString(), anyDouble(), anyInt()))
                    .thenReturn(List.of(row("Одеса", "CITY", "Одеська")));

            List<SettlementSearchResponse> result = service.search("•".repeat(47) + "оде");

            assertThat(result).hasSize(1);
            verify(cityRepository).searchByName(anyString(), anyString(), anyDouble(), anyInt());
        }

        @Test
        @DisplayName("a digit is trigram-bearing too — «12 Квітня» style names must stay findable")
        void should_queryTheRepository_when_theTermIsNumeric() {
            when(cityRepository.searchByName(anyString(), anyString(), anyDouble(), anyInt()))
                    .thenReturn(List.of());

            service.search("112");

            verify(cityRepository).searchByName(anyString(), anyString(), anyDouble(), anyInt());
        }
    }

    @Nested
    @DisplayName("executable query — what is bound into the repository call")
    class QueryBinding {

        @Test
        @DisplayName("binds an escaped prefix pattern, the bare term, the floor and the cap")
        void should_bindPatternTermFloorAndCap_when_termIsExecutable() {
            when(cityRepository.searchByName(anyString(), anyString(), anyDouble(), anyInt()))
                    .thenReturn(List.of(row("Львів", "CITY", "Львівська")));

            service.search("  Львів  ");

            ArgumentCaptor<String> pattern = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> term = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Double> floor = ArgumentCaptor.forClass(Double.class);
            ArgumentCaptor<Integer> cap = ArgumentCaptor.forClass(Integer.class);
            verify(cityRepository).searchByName(
                    pattern.capture(), term.capture(), floor.capture(), cap.capture());

            assertThat(pattern.getValue()).isEqualTo("Львів%");
            assertThat(term.getValue()).isEqualTo("Львів");
            assertThat(floor.getValue()).isEqualTo(SettlementSearchService.MIN_SIMILARITY);
            assertThat(cap.getValue()).isEqualTo(SettlementSearchService.MAX_RESULTS);
        }

        @Test
        @DisplayName("a typed LIKE wildcard is escaped, never left to match everything")
        void should_escapeLikeMetacharacters_when_termContainsThem() {
            when(cityRepository.searchByName(anyString(), anyString(), anyDouble(), anyInt()))
                    .thenReturn(List.of());

            // The fixture carries 3-character alphanumeric runs («abc», «def», …) because the
            // admission guard now requires one: the previous fixture «a%b_c\d» has no run longer
            // than a single character and is refused before the escape ever runs, which would have
            // turned this into a test of the guard rather than of the escaping.
            service.search("abc%def_ghi\\jkl");

            ArgumentCaptor<String> pattern = ArgumentCaptor.forClass(String.class);
            verify(cityRepository).searchByName(
                    pattern.capture(), anyString(), anyDouble(), anyInt());

            // Backslash escaped FIRST, so the escapes introduced for % and _ are not re-escaped.
            assertThat(pattern.getValue()).isEqualTo("abc\\%def\\_ghi\\\\jkl%");
        }

        @Test
        @DisplayName("every apostrophe variant folds onto U+2019 — the form cities.name_uk stores")
        void should_foldApostrophesOntoU2019_when_termCarriesAnyVariant() {
            when(cityRepository.searchByName(anyString(), anyString(), anyDouble(), anyInt()))
                    .thenReturn(List.of());

            // U+0027 straight, U+02BC modifier letter, U+2018 left single quote, U+00B4 acute.
            service.search("Кам'янка ʼ ‘ ´");

            ArgumentCaptor<String> term = ArgumentCaptor.forClass(String.class);
            verify(cityRepository).searchByName(
                    anyString(), term.capture(), anyDouble(), anyInt());

            assertThat(term.getValue())
                    .as("the KATOTTH import stores U+2019; folding onto U+0027 like the sibling "
                            + "discovery search does would drop 589 settlements from the prefix tier")
                    .isEqualTo("Кам’янка ’ ’ ’");
        }

        @Test
        @DisplayName("maps the projection to the public DTO, including the enum and oblast label")
        void should_mapProjectionToResponse_when_rowsAreReturned() {
            when(cityRepository.searchByName(anyString(), anyString(), anyDouble(), anyInt()))
                    .thenReturn(List.of(
                            row("Іванівка", "VILLAGE", "Полтавська"),
                            row("Іванівка", "SETTLEMENT", "Сумська")));

            List<SettlementSearchResponse> result = service.search("Іванівка");

            assertThat(result)
                    .extracting(SettlementSearchResponse::nameUk,
                            SettlementSearchResponse::settlementType,
                            SettlementSearchResponse::oblastNameUk)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(
                                    "Іванівка", SettlementType.VILLAGE, "Полтавська"),
                            org.assertj.core.groups.Tuple.tuple(
                                    "Іванівка", SettlementType.SETTLEMENT, "Сумська"));
            assertThat(result).allSatisfy(r ->
                    assertThat(r.settlementId()).as("the client stores the id, never the name")
                            .isNotNull());
        }
    }
}
