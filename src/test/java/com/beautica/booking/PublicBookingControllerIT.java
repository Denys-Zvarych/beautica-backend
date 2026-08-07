package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.dto.BookingSlugInfoResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Full-context integration test for the public guest-booking endpoint
 * {@code GET /api/v1/book/{slug}/info}. Seeds a master + active service via JDBC
 * (with a known {@code booking_slug}) and asserts the unauthenticated round-trip
 * through real Spring Security + Postgres. Confirms the {@code /api/v1/book/**}
 * permitAll matcher and the DB lookup wiring (Phase 13.1).
 */
@DisplayName("PublicBookingController — integration (Testcontainers, no auth)")
class PublicBookingControllerIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("GET /book/{slug}/info — 200 with master name + services, no Authorization header")
    void should_return200WithMasterAndServices_when_slugExists_noAuth() {
        String slug = "olena-koval-ab12";
        seedIndependentMasterWithService(slug, "Олена", "Коваль");

        ResponseEntity<BookingSlugInfoResponse> resp = restTemplate.getForEntity(
                "/api/v1/book/" + slug + "/info", BookingSlugInfoResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().masterName()).isEqualTo("Олена Коваль");
        assertThat(resp.getBody().services()).hasSize(1);
        assertThat(resp.getBody().services().get(0).name()).isEqualTo("Маникюр");
        assertThat(resp.getBody().services().get(0).priceFrom()).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("GET /book/{slug}/info — 404 when slug unknown, no auth required")
    void should_return404_when_slugUnknown() {
        ResponseEntity<String> resp = restTemplate.getForEntity(
                "/api/v1/book/no-such-slug/info", String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("GET /book/{slug}/info — 404 when the master owning the slug is inactive (active-filter)")
    void should_return404_when_masterInactive() {
        String slug = "inactive-master-cd34";
        seedMaster(slug, "Тест", "Майстер", "INDEPENDENT_MASTER", false);

        ResponseEntity<String> resp = restTemplate.getForEntity(
                "/api/v1/book/" + slug + "/info", String.class);

        // findByBookingSlugWithUser filters isActive = true, so a deactivated master's
        // public booking page must 404 even though the slug row exists.
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("GET /book/{slug}/info — 404 when the master's SALON was deactivated (salon-active guard)")
    void should_return404_when_slugInfoRequestedForInactiveMaster() {
        String slug = "closed-salon-master-gh78";
        // masters.is_active stays TRUE — only the salon is closed, exactly what deactivateSalon
        // leaves behind. Before the guard, findBySlug applied NO active filter whatsoever, so this
        // page kept rendering the master's name, avatar and full service menu with a live CTA.
        seedSalonMaster(slug, false);

        ResponseEntity<String> resp = restTemplate.getForEntity(
                "/api/v1/book/" + slug + "/info", String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("GET /book/{slug}/info — 200 when the master's salon is ACTIVE (guard is not over-broad)")
    void should_return200_when_slugInfoRequestedForMasterOfActiveSalon() {
        String slug = "open-salon-master-ij90";
        seedSalonMaster(slug, true);

        ResponseEntity<String> resp = restTemplate.getForEntity(
                "/api/v1/book/" + slug + "/info", String.class);

        assertThat(resp.getStatusCode())
                .as("a salon-employed master of an OPEN salon must keep a working booking page")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("V86 partial unique index — rejects a duplicate booking_slug across master types")
    void should_rejectDuplicateSlug_acrossMasterTypes() {
        String slug = "shared-slug-ef56";
        // First owner taken by an INDEPENDENT_MASTER row.
        seedMaster(slug, "Перший", "Майстер", "INDEPENDENT_MASTER", true);

        // A SALON_OWNER row in a *different* master type must NOT be allowed to reuse the
        // same slug — the single-table partial unique index (idx_masters_booking_slug)
        // enforces global uniqueness across every master type (V86 regression).
        assertThatThrownBy(() -> seedMaster(slug, "Другий", "Майстер", "SALON_OWNER", true))
                .isInstanceOf(DataAccessException.class);
    }

    private void seedIndependentMasterWithService(String slug, String first, String last) {
        UUID masterId = seedMaster(slug, first, last, "INDEPENDENT_MASTER", true);

        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, base_duration_minutes,
                                                 base_price, buffer_minutes_after, is_active, created_at, updated_at)
                VALUES (?, 'INDEPENDENT_MASTER', ?, 'Маникюр', ?, 60, 500.00, 0, true, NOW(), NOW())
                """, serviceDefId, masterId, resolveServiceTypeId());

        jdbcTemplate.update("""
                INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at)
                VALUES (?, ?, ?, true, NOW(), NOW())
                """, UUID.randomUUID(), masterId, serviceDefId);
    }

    /**
     * Resolves a real, selectable {@code service_types.id} (V111 made this column NOT NULL).
     */
    private UUID resolveServiceTypeId() {
        return jdbcTemplate.queryForObject(
                "SELECT st.id FROM service_types st "
                        + "JOIN platform_categories pc ON pc.name = st.platform_category_name "
                        + "WHERE st.is_active = TRUE AND pc.active = TRUE AND pc.status = 'APPROVED' "
                        + "ORDER BY st.name_uk LIMIT 1",
                UUID.class);
    }

    /**
     * Seeds an ACTIVE {@code SALON_MASTER} (with the given slug) employed by a salon whose
     * {@code is_active} carries {@code salonActive}. Mirrors
     * {@code MasterService#createMasterFromInvite}, which mints a booking slug for every invited
     * salon master — which is exactly why a closed salon's staff would otherwise keep live public
     * booking links.
     */
    private void seedSalonMaster(String slug, boolean salonActive) {
        UUID ownerId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO users (id, email, password_hash, role, first_name, last_name, is_active, email_verified)
                VALUES (?, ?, 'x', 'SALON_OWNER', 'Salon', 'Owner', true, true)
                """, ownerId, "owner-" + System.nanoTime() + "@beautica.test");

        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at)
                VALUES (?, ?, 'Test Salon', ?, NOW(), NOW())
                """, salonId, ownerId, salonActive);

        UUID staffUserId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO users (id, email, password_hash, role, first_name, last_name,
                                   avatar_url, bio, is_active, email_verified)
                VALUES (?, ?, 'x', 'SALON_MASTER', 'Тест', 'Майстер',
                        'https://cdn.example/a.jpg', 'bio', true, true)
                """, staffUserId, "staff-" + System.nanoTime() + "@beautica.test");

        jdbcTemplate.update("""
                INSERT INTO masters (id, user_id, salon_id, master_type, booking_slug, is_active, created_at, updated_at)
                VALUES (?, ?, ?, 'SALON_MASTER', ?, true, NOW(), NOW())
                """, UUID.randomUUID(), staffUserId, salonId, slug);
    }

    /** Seeds a user + a {@code masters} row with the given slug, type, and active flag; returns the master id. */
    private UUID seedMaster(String slug, String first, String last, String masterType, boolean active) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO users (id, email, password_hash, role, first_name, last_name,
                                   avatar_url, bio, is_active, email_verified)
                VALUES (?, ?, 'x', ?, ?, ?, 'https://cdn.example/a.jpg', 'bio', true, true)
                """, userId, "m-" + System.nanoTime() + "@beautica.test", masterType, first, last);

        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO masters (id, user_id, master_type, booking_slug, is_active, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, NOW(), NOW())
                """, masterId, userId, masterType, slug, active);
        return masterId;
    }
}
