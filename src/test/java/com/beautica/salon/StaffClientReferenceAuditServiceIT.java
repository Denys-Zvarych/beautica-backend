package com.beautica.salon;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.Role;
import com.beautica.salon.audit.AuditOutcome;
import com.beautica.salon.audit.StaffClientReferenceAuditResult;
import com.beautica.salon.audit.StaffClientReferenceType;
import com.beautica.salon.audit.StaffClientReferenceViolation;
import com.beautica.salon.service.StaffClientReferenceAuditService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-DB coverage for {@link StaffClientReferenceAuditService} — the salon-deletion safety audit
 * (Phase 289). Every violation fixture is inserted with raw SQL, bypassing the service layer
 * entirely: the controllers ({@code BookingController}, {@code ReviewController},
 * {@code FavoriteController}) all reject a non-{@code CLIENT} actor with 403, so the state this
 * audit checks for can only exist today via a seed script or direct DB write — exactly what these
 * tests simulate. See {@code docs/backend-phases/phase-289-staff-as-client-safety-audit.md}.
 */
@DisplayName("StaffClientReferenceAuditService — Phase 289 salon-deletion safety audit")
class StaffClientReferenceAuditServiceIT extends AbstractIntegrationTest {

    private static final String TEST_PASSWORD = "Str0ngP@ss1!";

    @Autowired
    private StaffClientReferenceAuditService auditService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("clean DB — a CLIENT user referenced everywhere, unrelated staff users with no "
            + "client-side references at all — reports CLEAN with no violations")
    void should_reportClean_when_noStaffReferencedAsClient() {
        Provider provider = createSalonWithMaster();
        UUID clientId = createUser("audit-clean-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        insertBooking(clientId, provider);
        insertReview(insertBooking(clientId, provider), clientId, provider);
        insertClientReview(insertBooking(clientId, provider), clientId, provider);
        // Unrelated staff accounts exist but are never referenced as a client anywhere.
        createUser("audit-clean-master-" + System.nanoTime() + "@beautica.test", "SALON_MASTER", provider.salonId());
        createUser("audit-clean-admin-" + System.nanoTime() + "@beautica.test", "SALON_ADMIN", provider.salonId());

        StaffClientReferenceAuditResult result = auditService.runAudit();

        assertThat(result.outcome())
                .as("a database with no rule violations must report CLEAN")
                .isEqualTo(AuditOutcome.CLEAN);
        assertThat(result.violations()).isEmpty();
    }

    @Test
    @DisplayName("SALON_MASTER referenced as bookings.client_id — exactly one BOOKING_CLIENT violation")
    void should_reportViolation_when_salonMasterReferencedAsBookingClient() {
        Provider provider = createSalonWithMaster();
        UUID staffUserId = createUser(
                "audit-bk-master-" + System.nanoTime() + "@beautica.test", "SALON_MASTER", provider.salonId());
        insertBooking(staffUserId, provider);

        StaffClientReferenceAuditResult result = auditService.runAudit();

        assertThat(result.outcome()).isEqualTo(AuditOutcome.VIOLATIONS_FOUND);
        assertThat(result.violations()).hasSize(1);
        StaffClientReferenceViolation violation = result.violations().get(0);
        assertThat(violation.userId()).isEqualTo(staffUserId);
        assertThat(violation.role()).isEqualTo(Role.SALON_MASTER);
        assertThat(violation.referenceType()).isEqualTo(StaffClientReferenceType.BOOKING_CLIENT);
        assertThat(violation.rowCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("SALON_MASTER referenced as reviews.client_id — exactly one REVIEW_CLIENT violation")
    void should_reportViolation_when_salonMasterReferencedAsReviewClient() {
        Provider provider = createSalonWithMaster();
        UUID staffUserId = createUser(
                "audit-rv-master-" + System.nanoTime() + "@beautica.test", "SALON_MASTER", provider.salonId());
        UUID bookingId = insertBooking(staffUserId, provider);
        insertReview(bookingId, staffUserId, provider);

        StaffClientReferenceAuditResult result = auditService.runAudit();

        assertThat(result.outcome()).isEqualTo(AuditOutcome.VIOLATIONS_FOUND);
        // The same booking's client_id (bookings.client_id) is ALSO the staff user, so this fixture
        // legitimately produces a BOOKING_CLIENT violation alongside the REVIEW_CLIENT one being
        // asserted — narrow the assertion to the reference type under test rather than hasSize(1).
        assertThat(result.violations())
                .filteredOn(v -> v.referenceType() == StaffClientReferenceType.REVIEW_CLIENT)
                .as("exactly one REVIEW_CLIENT violation for the staff user")
                .hasSize(1)
                .first()
                .satisfies(v -> {
                    assertThat(v.userId()).isEqualTo(staffUserId);
                    assertThat(v.role()).isEqualTo(Role.SALON_MASTER);
                    assertThat(v.rowCount()).isEqualTo(1);
                });
    }

    @Test
    @DisplayName("SALON_MASTER referenced as client_reviews.subject_client_id — exactly one "
            + "CLIENT_REVIEW_SUBJECT violation")
    void should_reportViolation_when_salonMasterReferencedAsClientReviewSubject() {
        Provider provider = createSalonWithMaster();
        UUID staffUserId = createUser(
                "audit-cr-master-" + System.nanoTime() + "@beautica.test", "SALON_MASTER", provider.salonId());
        UUID bookingId = insertBooking(staffUserId, provider);
        insertClientReview(bookingId, staffUserId, provider);

        StaffClientReferenceAuditResult result = auditService.runAudit();

        assertThat(result.outcome()).isEqualTo(AuditOutcome.VIOLATIONS_FOUND);
        // Same overlap note as the REVIEW_CLIENT test above — the booking's own client_id is the
        // staff user too, so narrow to the reference type under test.
        assertThat(result.violations())
                .filteredOn(v -> v.referenceType() == StaffClientReferenceType.CLIENT_REVIEW_SUBJECT)
                .as("exactly one CLIENT_REVIEW_SUBJECT violation for the staff user")
                .hasSize(1)
                .first()
                .satisfies(v -> {
                    assertThat(v.userId()).isEqualTo(staffUserId);
                    assertThat(v.role()).isEqualTo(Role.SALON_MASTER);
                    assertThat(v.rowCount()).isEqualTo(1);
                });
    }

    @Test
    @DisplayName("SALON_ADMIN referenced as bookings.client_id — the role predicate covers both "
            + "audited roles, not just SALON_MASTER")
    void should_reportViolation_when_salonAdminReferencedAsClient() {
        Provider provider = createSalonWithMaster();
        UUID staffUserId = createUser(
                "audit-bk-admin-" + System.nanoTime() + "@beautica.test", "SALON_ADMIN", provider.salonId());
        insertBooking(staffUserId, provider);

        StaffClientReferenceAuditResult result = auditService.runAudit();

        assertThat(result.outcome()).isEqualTo(AuditOutcome.VIOLATIONS_FOUND);
        assertThat(result.violations()).hasSize(1);
        StaffClientReferenceViolation violation = result.violations().get(0);
        assertThat(violation.userId()).isEqualTo(staffUserId);
        assertThat(violation.role()).isEqualTo(Role.SALON_ADMIN);
        assertThat(violation.referenceType()).isEqualTo(StaffClientReferenceType.BOOKING_CLIENT);
    }

    @Test
    @DisplayName("a CLIENT user referenced as bookings.client_id must NOT be flagged — guards "
            + "against an over-broad role predicate")
    void should_notFlagClientUser_when_clientReferencedAsClient() {
        Provider provider = createSalonWithMaster();
        UUID clientId = createUser("audit-notflag-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        insertBooking(clientId, provider);

        StaffClientReferenceAuditResult result = auditService.runAudit();

        assertThat(result.outcome()).isEqualTo(AuditOutcome.CLEAN);
        assertThat(result.violations())
                .as("a CLIENT-role user referenced as a client is legitimate, never a violation")
                .noneMatch(v -> v.userId().equals(clientId));
    }

    @Test
    @DisplayName("two offending bookings for the same SALON_MASTER user aggregate into ONE "
            + "violation row with rowCount == 2, not two separate rows")
    void should_aggregateRowCount_when_sameStaffUserHasMultipleOffendingBookings() {
        Provider provider = createSalonWithMaster();
        UUID staffUserId = createUser(
                "audit-agg-master-" + System.nanoTime() + "@beautica.test", "SALON_MASTER", provider.salonId());
        insertBooking(staffUserId, provider);
        insertBooking(staffUserId, provider);

        StaffClientReferenceAuditResult result = auditService.runAudit();

        assertThat(result.outcome()).isEqualTo(AuditOutcome.VIOLATIONS_FOUND);
        assertThat(result.violations()).hasSize(1);
        StaffClientReferenceViolation violation = result.violations().get(0);
        assertThat(violation.userId()).isEqualTo(staffUserId);
        assertThat(violation.referenceType()).isEqualTo(StaffClientReferenceType.BOOKING_CLIENT);
        assertThat(violation.rowCount())
                .as("two offending bookings for the same user must fold into ONE violation row")
                .isEqualTo(2);
    }

    // ── salon-scoped audit (2026 perf audit — the per-delete precondition) ────────────────────

    @Test
    @DisplayName("runAuditForSalon: SALON_MASTER referenced as bookings.client_id — the "
            + "salon-scoped query finds a violation belonging to ITS OWN salon's staff")
    void should_reportViolation_when_salonScopedStaffReferencedAsBookingClient_forOwnSalon() {
        Provider provider = createSalonWithMaster();
        UUID staffUserId = createUser(
                "audit-scoped-master-" + System.nanoTime() + "@beautica.test", "SALON_MASTER", provider.salonId());
        // Master row is what findSalonStaffUserIds actually joins through for SALON_MASTER.
        UUID masterRowId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                masterRowId, staffUserId, provider.salonId());
        insertBooking(staffUserId, provider);

        StaffClientReferenceAuditResult result = auditService.runAuditForSalon(provider.salonId());

        assertThat(result.outcome()).isEqualTo(AuditOutcome.VIOLATIONS_FOUND);
        assertThat(result.violations()).hasSize(1);
        StaffClientReferenceViolation violation = result.violations().get(0);
        assertThat(violation.userId()).isEqualTo(staffUserId);
        assertThat(violation.role()).isEqualTo(Role.SALON_MASTER);
        assertThat(violation.referenceType()).isEqualTo(StaffClientReferenceType.BOOKING_CLIENT);
        assertThat(violation.rowCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("runAuditForSalon: SALON_ADMIN referenced as bookings.client_id — the "
            + "salon-scoped query finds a violation for an admin (users.salon_id, no masters row)")
    void should_reportViolation_when_salonScopedAdminReferencedAsBookingClient_forOwnSalon() {
        Provider provider = createSalonWithMaster();
        UUID staffUserId = createUser(
                "audit-scoped-admin-" + System.nanoTime() + "@beautica.test", "SALON_ADMIN", provider.salonId());
        insertBooking(staffUserId, provider);

        StaffClientReferenceAuditResult result = auditService.runAuditForSalon(provider.salonId());

        assertThat(result.outcome()).isEqualTo(AuditOutcome.VIOLATIONS_FOUND);
        assertThat(result.violations())
                .filteredOn(v -> v.userId().equals(staffUserId))
                .hasSize(1)
                .first()
                .satisfies(v -> {
                    assertThat(v.role()).isEqualTo(Role.SALON_ADMIN);
                    assertThat(v.referenceType()).isEqualTo(StaffClientReferenceType.BOOKING_CLIENT);
                });
    }

    @Test
    @DisplayName("runAuditForSalon: a violation belonging to a DIFFERENT salon's staff must NOT "
            + "be reported — proves the scoping actually filters by salonId, not just by role")
    void should_notReportViolation_when_staffBelongsToADifferentSalon() {
        Provider staffSalon = createSalonWithMaster();
        Provider otherSalon = createSalonWithMaster();
        UUID staffUserId = createUser(
                "audit-cross-salon-master-" + System.nanoTime() + "@beautica.test",
                "SALON_MASTER",
                staffSalon.salonId());
        UUID masterRowId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                masterRowId, staffUserId, staffSalon.salonId());
        // The staff user of staffSalon books a service performed by otherSalon's own master —
        // a real cross-salon booking, exactly like a staff member visiting a rival salon as a
        // paying customer would create.
        insertBooking(staffUserId, otherSalon);

        StaffClientReferenceAuditResult resultForOtherSalon = auditService.runAuditForSalon(otherSalon.salonId());
        StaffClientReferenceAuditResult resultForStaffSalon = auditService.runAuditForSalon(staffSalon.salonId());

        assertThat(resultForOtherSalon.outcome())
                .as("otherSalon did not employ the staff member referenced as this booking's "
                        + "client — a salon-scoped query keyed on the WRONG salonId must report CLEAN "
                        + "even though the violating row itself sits on otherSalon's booking")
                .isEqualTo(AuditOutcome.CLEAN);
        assertThat(resultForOtherSalon.violations()).isEmpty();

        assertThat(resultForStaffSalon.outcome())
                .as("staffSalon DID employ the staff member, so scoping to the CORRECT salonId "
                        + "must still find the violation")
                .isEqualTo(AuditOutcome.VIOLATIONS_FOUND);
        assertThat(resultForStaffSalon.violations())
                .hasSize(1)
                .first()
                .satisfies(v -> assertThat(v.userId()).isEqualTo(staffUserId));
    }

    @Test
    @DisplayName("runAuditForSalon: a salon with no staff at all short-circuits to CLEAN without "
            + "any violation query")
    void should_reportClean_when_salonHasNoStaff() {
        UUID ownerId = createUser("audit-nostaff-owner-" + System.nanoTime() + "@beautica.test", "SALON_OWNER", null);
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at, city_id) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW(), ?)",
                salonId, ownerId, "Salon-" + salonId, testCityId());

        StaffClientReferenceAuditResult result = auditService.runAuditForSalon(salonId);

        assertThat(result.outcome()).isEqualTo(AuditOutcome.CLEAN);
        assertThat(result.violations()).isEmpty();
    }

    // ── seeding fixtures — mirrors ClientReviewIT's local house-convention fixtures ────────────

    /**
     * {@code masterServiceId} is created ONCE per fixture and reused across every booking the test
     * inserts against it — {@code ux_service_def_owner_service_type_active} is a partial UNIQUE
     * index on {@code (owner_type, owner_id, service_type_id)}, and {@link #resolveServiceTypeId}
     * is deterministic, so a second {@code service_definitions} INSERT for the same salon would
     * collide.
     */
    private record Provider(UUID salonId, UUID masterId, UUID masterServiceId) {}

    private Provider createSalonWithMaster() {
        UUID ownerId = createUser("audit-owner-" + System.nanoTime() + "@beautica.test", "SALON_OWNER", null);
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at, city_id) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW(), ?)",
                salonId, ownerId, "Salon-" + salonId, testCityId());

        UUID providerUserId = createUser(
                "audit-provider-" + System.nanoTime() + "@beautica.test", "SALON_MASTER", salonId);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                masterId, providerUserId, salonId);

        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'SALON', ?, 'Test Service', ?, 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, salonId, resolveServiceTypeId());
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);

        return new Provider(salonId, masterId, masterServiceId);
    }

    private UUID createUser(String email, String role, UUID salonId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, is_active, email_verified) "
                        + "VALUES (?, ?, ?, ?, ?, true, true)",
                id, email, passwordEncoder.encode(TEST_PASSWORD), role, salonId);
        return id;
    }

    private UUID resolveServiceTypeId() {
        return jdbcTemplate.queryForObject(
                "SELECT st.id FROM service_types st "
                        + "JOIN platform_categories pc ON pc.name = st.platform_category_name "
                        + "WHERE st.is_active = TRUE AND pc.active = TRUE AND pc.status = 'APPROVED' "
                        + "ORDER BY st.name_uk LIMIT 1",
                UUID.class);
    }

    /** Bookings need no particular status for this audit — it reads every row, regardless of status. */
    private UUID insertBooking(UUID clientId, Provider provider) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'COMPLETED', NOW() - interval '2 hours', NOW() - interval '1 hour', "
                        + "500.00, 60, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, provider.masterId(), provider.masterServiceId(), provider.salonId());
        return bookingId;
    }

    private void insertReview(UUID bookingId, UUID clientId, Provider provider) {
        jdbcTemplate.update(
                "INSERT INTO reviews (id, booking_id, client_id, master_id, salon_id, rating, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 5, NOW(), NOW())",
                UUID.randomUUID(), bookingId, clientId, provider.masterId(), provider.salonId());
    }

    private void insertClientReview(UUID bookingId, UUID subjectClientId, Provider provider) {
        jdbcTemplate.update(
                "INSERT INTO client_reviews (id, booking_id, subject_client_id, author_master_id, salon_id, "
                        + "rating, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 5, NOW(), NOW())",
                UUID.randomUUID(), bookingId, subjectClientId, provider.masterId(), provider.salonId());
    }
}
