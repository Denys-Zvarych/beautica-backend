package com.beautica.user;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.BookingTestFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 488 — FK completeness for {@code SALON_OWNER} self-delete, mirroring
 * {@link StaffAccountDeleteFkCompletenessIT}. The owner holds a row in every {@code users(id)}-referencing table
 * an owner can plausibly populate (incl. {@code salons.owner_id}, the one FK that is NO ACTION), self-deletes,
 * and then EVERY foreign key to {@code users} — enumerated from {@code pg_constraint}, never hand-listed, so a
 * future migration's FK is covered automatically — is asserted to reference the deleted id nowhere. The
 * polymorphic id columns of {@code docs/release/delete-owner-account-template.sql} (no FK) are checked too.
 */
@DisplayName("DELETE /api/v1/users/me — SALON_OWNER: no row references the deleted owner (Phase 488 FK completeness)")
class OwnerAccountDeleteFkCompletenessIT extends AbstractIntegrationTest {

    private static final OffsetDateTime PAST = OffsetDateTime.now().minusDays(2);

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;

    private BookingTestFixtures fixtures;
    private ClientSelfDeleteTestFixtures csd;

    // platform_categories / service_type_suggestion outlive cleanDb() — cleaned explicitly (same as the staff IT).
    private Long insertedCategoryId;
    private UUID insertedSuggestionId;

    @BeforeEach
    void setUp() {
        fixtures = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
        csd = new ClientSelfDeleteTestFixtures(jdbcTemplate, passwordEncoder);
    }

    @AfterEach
    void cleanUpAuditTables() {
        if (insertedCategoryId != null) {
            jdbcTemplate.update("DELETE FROM platform_categories WHERE id = ?", insertedCategoryId);
        }
        if (insertedSuggestionId != null) {
            jdbcTemplate.update("DELETE FROM service_type_suggestion WHERE id = ?", insertedSuggestionId);
        }
    }

    @Test
    @DisplayName("owner with a deleted salon and a row in every reachable users(id) table → 204, and no FK "
            + "column or polymorphic id column references the deleted owner")
    void should_leaveNoReferenceToOwner_when_ownerSelfDeletes() throws Exception {
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        UUID ownerId = salon.ownerId();
        UUID ownerMasterId = fixtures.createOwnerAsMaster(salon.salonId(), ownerId);
        UUID clientId = csd.createClient();
        String token = fixtures.tokenFor(emailOf(ownerId)); // refresh_tokens.user_id

        UUID ownerMasterServiceId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, "
                + "updated_at) VALUES (?, ?, ?, true, NOW(), NOW())", ownerMasterServiceId, ownerMasterId,
                salon.serviceDefId());
        UUID appointmentId = csd.insertAppointmentHeader(clientId, salon.salonId(), "COMPLETED");
        UUID walkInId = csd.insertBooking(clientId, ownerMasterId, ownerMasterServiceId, salon.salonId(),
                "COMPLETED", PAST, appointmentId);
        jdbcTemplate.update("UPDATE bookings SET created_by_user_id = ? WHERE id = ?", ownerId, walkInId);
        jdbcTemplate.update("UPDATE appointments SET created_by_user_id = ? WHERE id = ?", ownerId, appointmentId);
        csd.insertDeviceToken(ownerId);
        csd.insertMediaFile(ownerId);
        csd.insertPasswordResetTicket(ownerId);
        insertedCategoryId = csd.insertPlatformCategoryRequest(ownerId);
        insertedSuggestionId = csd.insertServiceTypeSuggestion(ownerId);
        jdbcTemplate.update("""
                INSERT INTO in_app_notification (id, recipient_user_id, type, booking_id, dedup_key)
                VALUES (?, ?, 'BOOKING_DECLINED', ?, ?)
                """, UUID.randomUUID(), ownerId, walkInId, "BOOKING_DECLINED:" + walkInId);
        // The salon is deleted the way production leaves it: staff disposed (SAL-21), salon inactive.
        jdbcTemplate.update("DELETE FROM masters WHERE id = ?", salon.masterId());
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", salon.masterUserId());
        jdbcTemplate.update("UPDATE masters SET is_active = false WHERE id = ?", ownerMasterId);
        jdbcTemplate.update("UPDATE salons SET is_active = false WHERE id = ?", salon.salonId());
        List<FkColumn> fks = foreignKeysToUsers();
        assertThat(referencingColumns(fks, ownerId))
                .as("fixture check — the owner is genuinely referenced before the delete")
                .contains("salons.owner_id", "refresh_tokens.user_id", "device_tokens.user_id",
                        "masters.user_id", "bookings.created_by_user_id", "in_app_notification.recipient_user_id");

        ResponseEntity<String> response = restTemplate.exchange("/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);

        assertThat(response.getStatusCode()).as("a 500 here means an FK was not satisfied — body: %s",
                response.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(csd.userExists(ownerId)).isFalse();
        assertThat(referencingColumns(fks, ownerId)).as("FK columns still naming the deleted owner").isEmpty();
        assertThat(csd.count("SELECT COUNT(*) FROM media_files WHERE entity_type = 'USER' AND entity_id = ?", ownerId))
                .isZero();
        assertThat(csd.count("SELECT COUNT(*) FROM content_reports WHERE target_type = 'CLIENT_PROFILE' "
                + "AND target_id = ?", ownerId)).isZero();
        assertThat(csd.count("SELECT COUNT(*) FROM notification_outbox WHERE aggregate_id = ?", ownerId)).isZero();
        assertThat(csd.count("SELECT COUNT(*) FROM favorites WHERE target_type = 'MASTER' AND target_id = ?",
                ownerMasterId)).isZero();
        assertThat(csd.bookingExists(walkInId)).as("client history survives").isTrue();
        assertThat(csd.masterUserId(ownerMasterId)).as("owner-master is a name stub").isNull();
    }

    private record FkColumn(String table, String column) {}

    /** Every single-column FK whose target is {@code users}, straight from the catalog. */
    private List<FkColumn> foreignKeysToUsers() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT c.conrelid::regclass::text AS tbl, a.attname AS col
                FROM pg_constraint c
                JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
                WHERE c.contype = 'f' AND c.confrelid = 'users'::regclass AND array_length(c.conkey, 1) = 1
                ORDER BY 1, 2
                """);
        assertThat(rows).as("catalog enumeration found the users FKs").isNotEmpty();
        return rows.stream().map(r -> new FkColumn((String) r.get("tbl"), (String) r.get("col"))).toList();
    }

    private List<String> referencingColumns(List<FkColumn> fks, UUID userId) {
        return fks.stream()
                .filter(fk -> Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM " + fk.table() + " WHERE " + fk.column() + " = ?)",
                        Boolean.class, userId)))
                .map(fk -> fk.table() + "." + fk.column())
                .toList();
    }

    private String emailOf(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }
}
