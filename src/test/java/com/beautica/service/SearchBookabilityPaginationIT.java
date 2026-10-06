package com.beautica.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.support.BookableMasterSeeder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.beautica.service.BookabilityHttp.ids;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Search counts and pagination under the bookability gate: {@code totalElements}/{@code totalPages}
 * must count exactly the rows a client can page through — non-bookable providers are excluded from
 * the COUNT as well as the content, and a salon with several bookable masters (which the bookable
 * rows join can multiply) is counted and returned ONCE.
 */
@DisplayName("Search pagination + totals respect the bookability gate (full HTTP + real Postgres)")
class SearchBookabilityPaginationIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private BookabilityHttp http;

    @BeforeEach
    void setUp() {
        http = new BookabilityHttp(restTemplate, objectMapper);
    }

    @Test
    @DisplayName("master browse: 5 bookable + 3 non-bookable, size=2 → totalElements 5, totalPages 3, "
            + "pages 2/2/1 covering each bookable master exactly once")
    void should_countAndPageOnlyBookableMasters_when_masterBrowsePaged() throws Exception {
        List<String> bookable = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            UUID id = BookableMasterSeeder.insertIndependentMaster(jdbcTemplate, "Pagina" + (char) ('a' + i));
            BookableMasterSeeder.makeIndependentBookable(jdbcTemplate, id);
            bookable.add(id.toString());
        }
        UUID serviceOnly = BookableMasterSeeder.insertIndependentMaster(jdbcTemplate, "Servonly");
        BookableMasterSeeder.assignNewIndependentService(jdbcTemplate, serviceOnly);
        UUID scheduleOnly = BookableMasterSeeder.insertIndependentMaster(jdbcTemplate, "Schedonly");
        BookableMasterSeeder.seedUsableSchedule(jdbcTemplate, scheduleOnly);
        BookableMasterSeeder.insertIndependentMaster(jdbcTemplate, "Bareonly");

        List<String> seen = pageThrough("/api/v1/search/masters", "masterId", 5, List.of(2, 2, 1));

        assertThat(seen).containsExactlyInAnyOrderElementsOf(bookable);
    }

    @Test
    @DisplayName("salon browse: a salon with 3 bookable masters + an unconfigured owner-master counts ONCE; "
            + "3 bookable + 2 non-bookable salons, size=2 → totalElements 3, pages 2/1, no duplicates")
    void should_countEachBookableSalonOnce_when_salonBrowsePaged() throws Exception {
        UUID crowded = BookableMasterSeeder.insertSalon(jdbcTemplate, "Crowded Studio");
        for (int i = 0; i < 3; i++) {
            BookableMasterSeeder.addBookableSalonMaster(jdbcTemplate, crowded);
        }
        BookableMasterSeeder.insertSalonMaster(jdbcTemplate, crowded);
        UUID single1 = BookableMasterSeeder.insertSalon(jdbcTemplate, "Single One");
        BookableMasterSeeder.addBookableSalonMaster(jdbcTemplate, single1);
        UUID single2 = BookableMasterSeeder.insertSalon(jdbcTemplate, "Single Two");
        BookableMasterSeeder.addBookableSalonMaster(jdbcTemplate, single2);
        UUID unscheduled = BookableMasterSeeder.insertSalon(jdbcTemplate, "Unscheduled Studio");
        BookableMasterSeeder.assignNewSalonService(jdbcTemplate, unscheduled,
                BookableMasterSeeder.insertSalonMaster(jdbcTemplate, unscheduled));
        BookableMasterSeeder.insertSalon(jdbcTemplate, "Empty Studio");

        List<String> seen = pageThrough("/api/v1/search/salons", "salonId", 3, List.of(2, 1));

        assertThat(seen).containsExactlyInAnyOrder(crowded.toString(), single1.toString(), single2.toString());
    }

    @Test
    @DisplayName("salon serviceTypeSlugs (dynamic path): 3 bookable masters sharing the filtered service "
            + "in one salon → that salon appears once; totalElements matches the content")
    void should_returnSalonOnce_when_severalBookableMastersShareFilteredType() throws Exception {
        UUID crowded = BookableMasterSeeder.insertSalon(jdbcTemplate, "Shared Type Studio");
        UUID first = BookableMasterSeeder.insertSalonMaster(jdbcTemplate, crowded);
        UUID defId = BookableMasterSeeder.assignNewSalonService(jdbcTemplate, crowded, first);
        BookableMasterSeeder.seedUsableSchedule(jdbcTemplate, first);
        for (int i = 0; i < 2; i++) {
            UUID peer = BookableMasterSeeder.insertSalonMaster(jdbcTemplate, crowded);
            assign(peer, defId);
            BookableMasterSeeder.seedUsableSchedule(jdbcTemplate, peer);
        }
        UUID other = BookableMasterSeeder.insertSalon(jdbcTemplate, "Other Type Studio");
        UUID otherMaster = BookableMasterSeeder.insertSalonMaster(jdbcTemplate, other);
        assign(otherMaster, salonDefinitionOfSameType(other, defId));
        BookableMasterSeeder.seedUsableSchedule(jdbcTemplate, otherMaster);
        String slug = jdbcTemplate.queryForObject(
                "SELECT st.slug FROM service_types st JOIN service_definitions sd ON sd.service_type_id = st.id "
                        + "WHERE sd.id = ?", String.class, defId);

        List<String> seen = pageThrough("/api/v1/search/salons?serviceTypeSlugs=" + slug, "salonId", 2, List.of(2));

        assertThat(seen).containsExactlyInAnyOrder(crowded.toString(), other.toString());
    }

    @Test
    @DisplayName("salon ?q (static name path): 2 bookable + 2 non-bookable name matches → totalElements 2, "
            + "content 2")
    void should_countOnlyBookableNameMatches_when_salonSearchedByName() throws Exception {
        UUID a = BookableMasterSeeder.insertSalon(jdbcTemplate, "Lumina Alpha");
        BookableMasterSeeder.addBookableSalonMaster(jdbcTemplate, a);
        BookableMasterSeeder.addBookableSalonMaster(jdbcTemplate, a);
        UUID b = BookableMasterSeeder.insertSalon(jdbcTemplate, "Lumina Beta");
        BookableMasterSeeder.addBookableSalonMaster(jdbcTemplate, b);
        UUID c = BookableMasterSeeder.insertSalon(jdbcTemplate, "Lumina Gamma");
        BookableMasterSeeder.seedUsableSchedule(jdbcTemplate, BookableMasterSeeder.insertSalonMaster(jdbcTemplate, c));
        BookableMasterSeeder.insertSalon(jdbcTemplate, "Lumina Delta");

        List<String> seen = pageThrough("/api/v1/search/salons?q=Lumina", "salonId", 2, List.of(2));

        assertThat(seen).containsExactlyInAnyOrder(a.toString(), b.toString());
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    /**
     * Walks pages of size {@code max(expectedPageSizes)} (or 20 for a single page), asserting every
     * page's {@code totalElements}, {@code totalPages} and row count, that the page after the last is
     * empty, and that no id repeats. Returns every id seen.
     */
    private List<String> pageThrough(String url, String idField, long expectedTotal,
                                     List<Integer> expectedPageSizes) throws Exception {
        int size = expectedPageSizes.size() == 1 ? 20 : expectedPageSizes.get(0);
        String sep = url.contains("?") ? "&" : "?";
        List<String> seen = new ArrayList<>();
        for (int page = 0; page < expectedPageSizes.size(); page++) {
            JsonNode body = http.get(url + sep + "page=" + page + "&size=" + size);
            assertThat(body.path("totalElements").asLong()).as("page %d totalElements", page).isEqualTo(expectedTotal);
            assertThat(body.path("totalPages").asInt()).as("page %d totalPages", page).isEqualTo(expectedPageSizes.size());
            List<String> ids = ids(body, idField);
            assertThat(ids).as("page %d content", page).hasSize(expectedPageSizes.get(page));
            seen.addAll(ids);
        }
        JsonNode beyond = http.get(url + sep + "page=" + expectedPageSizes.size() + "&size=" + size);
        assertThat(ids(beyond, idField)).as("page past the last").isEmpty();
        assertThat(seen).as("no row repeats across pages").doesNotHaveDuplicates();
        return seen;
    }

    /** A SALON-owned definition in {@code salonId} on the same service type as {@code templateDefId}. */
    private UUID salonDefinitionOfSameType(UUID salonId, UUID templateDefId) {
        UUID defId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, price_type, base_price, buffer_minutes_after, "
                        + "is_active, created_at, updated_at) "
                        + "SELECT ?, 'SALON', ?, name, service_type_id, 60, 'FIXED', 500.00, 0, true, NOW(), NOW() "
                        + "FROM service_definitions WHERE id = ?",
                defId, salonId, templateDefId);
        return defId;
    }

    private void assign(UUID masterId, UUID defId) {
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                UUID.randomUUID(), masterId, defId);
    }
}
