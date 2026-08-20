package com.beautica.migration;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.enums.BookingSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the migration PAIR {@code V139__appointments_staff_source.sql} +
 * {@code V140__appointments_staff_source_validate.sql}: the widened {@code chk_appointment_source}
 * domain, the rewritten polymorphic {@code chk_appointment_guest_fields}, and the
 * {@code created_by_user_id} audit column + its FK + its partial index — modelled directly on
 * {@link V137StaffBookingSourceMigrationTest}, the equivalent test for the child {@code bookings}
 * row this header aggregates.
 *
 * <p>Reuses {@link BookingMigrationFixtures#seedBookingGraph} rather than a parallel fixture:
 * {@code appointments} needs only the {@code client_id} and {@code created_by_user_id} FKs onto
 * {@code users} that fixture already seeds — {@code appointments} carries no {@code master_id}.
 *
 * <p>All inserts go through {@code JdbcTemplate}, not JPA: the subject is DB-level CHECK behaviour,
 * and routing through {@code Appointment}'s own factory guards would mask it.
 *
 * <p><strong>Every reject test names the constraint it expects</strong>, for the same reason
 * {@link V137StaffBookingSourceMigrationTest} does: {@code appointments} carries more than one
 * rejecter surfacing the identical {@link DataIntegrityViolationException}, so a bare
 * {@code isInstanceOf} assertion would stay green even after the branch under test is deleted.
 */
@DisplayName("V139/V140 migration — STAFF appointment header source")
class V139AppointmentsStaffSourceMigrationTest extends AbstractIntegrationTest {

    private static final String INSERT_WITH_IDENTITY = """
            INSERT INTO appointments (id, client_id, status, booking_source,
                guest_name, guest_surname, guest_phone, cancel_token,
                created_by_user_id, created_at, updated_at)
            VALUES (?, ?, 'CONFIRMED', ?, ?, ?, ?, ?, ?, NOW(), NOW())
            """;

    // ── schema shape ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("should add a nullable created_by_user_id UUID column")
    void should_addCreatedByUserIdColumn_when_migrationApplied() {
        String dataType = jdbcTemplate.queryForObject("""
                SELECT data_type FROM information_schema.columns
                WHERE table_name = 'appointments' AND column_name = 'created_by_user_id'
                """, String.class);
        String isNullable = jdbcTemplate.queryForObject("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_name = 'appointments' AND column_name = 'created_by_user_id'
                """, String.class);

        assertThat(dataType).isEqualTo("uuid");
        assertThat(isNullable).isEqualTo("YES");
    }

    @Test
    @DisplayName("should index created_by_user_id partially on IS NOT NULL")
    void should_createPartialIndex_when_migrationApplied() {
        String indexDef = jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_appointments_created_by'",
                String.class);

        assertThat(indexDef)
                .contains("created_by_user_id")
                .contains("WHERE (created_by_user_id IS NOT NULL)");
    }

    @Test
    @DisplayName("should round-trip created_by_user_id — the staff creator is actually persisted")
    void should_persistCreatedByUserId_when_staffWalkInInserted() {
        BookingMigrationFixtures.Ids ids = BookingMigrationFixtures.seedBookingGraph(jdbcTemplate);

        UUID appointmentId = insertStaffWalkIn(ids.staffUserId());

        UUID storedCreator = jdbcTemplate.queryForObject(
                "SELECT created_by_user_id FROM appointments WHERE id = ?", UUID.class, appointmentId);
        assertThat(storedCreator)
                .as("the audit column must store the creator it was given, actual=%s", storedCreator)
                .isEqualTo(ids.staffUserId());
    }

    @Test
    @DisplayName("created_by_user_id is a real FK — an id that is not a users row is rejected")
    void should_rejectStaffHeader_when_createdByUserIdIsNotAUser() {
        assertThatThrownBy(() -> jdbcTemplate.update(INSERT_WITH_IDENTITY,
                UUID.randomUUID(), null, "STAFF", "Олена", "Коваль", "+380501234567", null,
                UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_appointments_created_by");
    }

    @Test
    @DisplayName("should REJECT deleting a staff user whose appointments still attribute to them")
    void should_restrictUserDelete_when_appointmentReferencesCreator() {
        BookingMigrationFixtures.Ids ids = BookingMigrationFixtures.seedBookingGraph(jdbcTemplate);
        UUID appointmentId = insertStaffWalkIn(ids.staffUserId());

        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM users WHERE id = ?", ids.staffUserId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_appointments_created_by");

        UUID survivingCreator = jdbcTemplate.queryForObject(
                "SELECT created_by_user_id FROM appointments WHERE id = ?", UUID.class, appointmentId);
        assertThat(survivingCreator)
                .as("the attribution must survive the rejected delete un-NULLed, actual=%s", survivingCreator)
                .isEqualTo(ids.staffUserId());
    }

    @Test
    @DisplayName("created_by_user_id's FK declares ON DELETE RESTRICT ('r' in pg_constraint)")
    void should_declareRestrictDeleteAction_when_migrationApplied() {
        String deleteAction = jdbcTemplate.queryForObject("""
                SELECT confdeltype FROM pg_constraint
                WHERE conrelid = 'appointments'::regclass AND contype = 'f'
                  AND conkey = ARRAY[(SELECT attnum FROM pg_attribute
                        WHERE attrelid = 'appointments'::regclass AND attname = 'created_by_user_id')]
                """, String.class);

        assertThat(deleteAction)
                .as("'n' = SET NULL, 'r' = RESTRICT, 'a' = NO ACTION, 'c' = CASCADE; actual=%s", deleteAction)
                .isEqualTo("r");
    }

    // ── V140: NOT VALID → VALIDATE actually ran ────────────────────────────────

    /**
     * The assertion that catches a V140 that silently never ran: {@code convalidated} stays
     * {@code false} on a constraint added {@code NOT VALID} until an explicit
     * {@code VALIDATE CONSTRAINT} commits.
     */
    @Test
    @DisplayName("all three V139 constraints report convalidated = true after V140")
    void should_reportConstraintsValidated_after_V140() {
        List<String> names = List.of(
                "chk_appointment_source", "chk_appointment_guest_fields", "fk_appointments_created_by");

        for (String name : names) {
            Boolean validated = jdbcTemplate.queryForObject(
                    "SELECT convalidated FROM pg_constraint WHERE conname = ?", Boolean.class, name);
            assertThat(validated)
                    .as("%s must be VALIDATEd by V140, actual=%s", name, validated)
                    .isTrue();
        }
    }

    // ── chk_appointment_source ────────────────────────────────────────────────

    @Test
    @DisplayName("chk_appointment_source admits exactly APP, LINK and STAFF — no more, no fewer")
    void should_admitExactlyAppLinkStaff_when_sourceDomainInspected() {
        String constraintDef = jdbcTemplate.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'chk_appointment_source'",
                String.class);

        assertThat(constraintDef)
                .as("actual=%s", constraintDef)
                .contains("'APP'")
                .contains("'LINK'")
                .contains("'STAFF'");
        assertThat(constraintDef.split("'").length)
                .as("a fourth literal would mean the domain was widened without updating BookingSource")
                .isEqualTo(7); // 3 literals => 6 quotes => 7 split segments
    }

    @Test
    @DisplayName("BookingSource's constants and chk_appointment_source's domain are the same set")
    void should_matchDbDomain_when_bookingSourceConstantsEnumerated() {
        String constraintDef = jdbcTemplate.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'chk_appointment_source'",
                String.class);

        List<String> enumNames = Arrays.stream(BookingSource.values()).map(Enum::name).toList();

        assertThat(enumNames)
                .as("enum constants must all appear in the DB domain, actual domain=%s", constraintDef)
                .allSatisfy(name -> assertThat(constraintDef).contains("'" + name + "'"));
        assertThat(constraintDef.split("'").length)
                .as("the DB domain must carry no literal the enum lacks")
                .isEqualTo(enumNames.size() * 2 + 1);
    }

    /**
     * <b>Inverted and relocated from {@code StaffBookingIT.SingleServiceBoundary}</b>
     * (Phase 22.8/22.15). That test used to pin {@code chk_appointment_source} STILL excluding
     * STAFF — the premise Phase 22.2's single-service scope decision rested on. V139 inverts that
     * premise on purpose, so this is the live-constraint read moved here and flipped, PLUS the
     * assertion the original never covered: the STAFF branch of {@code chk_appointment_guest_fields}
     * is live too, not just the source domain — a migration that widened only the first constraint
     * would pass this file's other tests but leave every STAFF header insert rejected at runtime.
     */
    @Test
    @DisplayName("chk_appointment_source now admits STAFF, and chk_appointment_guest_fields carries its branch")
    void should_admitStaffInAppointmentSource_when_readingTheLiveConstraint() {
        String sourceDefinition = jdbcTemplate.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'chk_appointment_source'",
                String.class);
        String guestFieldsDefinition = jdbcTemplate.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'chk_appointment_guest_fields'",
                String.class);

        assertThat(sourceDefinition).contains("'APP'").contains("'LINK'").contains("'STAFF'");
        assertThat(guestFieldsDefinition)
                .as("the guest-fields CHECK must carry a STAFF branch too, actual=%s", guestFieldsDefinition)
                .contains("'STAFF'");
    }

    /**
     * {@code chk_appointment_source} cannot be provoked in isolation: every branch of
     * {@code chk_appointment_guest_fields} pins {@code booking_source} to a specific literal, so an
     * out-of-domain source violates that constraint too, and it is the one Postgres reports.
     */
    @Test
    @DisplayName("should reject an unknown booking_source — matches no identity branch")
    void should_rejectUnknownSource_when_bookingSourceIsGarbage() {
        BookingMigrationFixtures.Ids ids = BookingMigrationFixtures.seedBookingGraph(jdbcTemplate);

        assertThatThrownBy(() -> jdbcTemplate.update(INSERT_WITH_IDENTITY,
                UUID.randomUUID(), null, "KIOSK", "Олена", "Коваль", "+380501234567", null,
                ids.staffUserId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_appointment_guest_fields");
    }

    // ── chk_appointment_guest_fields — STAFF branch accepts ────────────────────

    @Test
    @DisplayName("a STAFF walk-in header with name + surname + phone, no client_id, no cancel_token")
    void should_acceptStaffWalkInHeader_when_guestIdentityComplete() {
        BookingMigrationFixtures.Ids ids = BookingMigrationFixtures.seedBookingGraph(jdbcTemplate);

        UUID appointmentId = insertStaffWalkIn(ids.staffUserId());

        assertThat(sourceOf(appointmentId)).isEqualTo("STAFF");
    }

    /**
     * Guards the "future linked-client phase needs ZERO migration" promise carried over from V137.
     * No writer uses this mode yet — the schema does.
     */
    @Test
    @DisplayName("a STAFF header carrying only client_id (deferred linked-client mode stays schema-legal)")
    void should_acceptStaffLinkedClientHeader_when_clientIdSetAndGuestFieldsNull() {
        BookingMigrationFixtures.Ids ids = BookingMigrationFixtures.seedBookingGraph(jdbcTemplate);
        UUID appointmentId = UUID.randomUUID();

        jdbcTemplate.update(INSERT_WITH_IDENTITY,
                appointmentId, ids.clientId(), "STAFF", null, null, null, null, ids.staffUserId());

        assertThat(sourceOf(appointmentId)).isEqualTo("STAFF");
    }

    // ── chk_appointment_guest_fields — STAFF branch rejects ────────────────────

    @Test
    @DisplayName("a STAFF header with a cancel_token (staff visits have no guest cancel link)")
    void should_rejectStaffHeader_when_cancelTokenPresent() {
        assertStaffRowRejected(null, "Олена", "Коваль", "+380501234567", UUID.randomUUID());
    }

    @Test
    @DisplayName("a STAFF header carrying BOTH a client_id and guest identity")
    void should_rejectStaffHeader_when_bothClientIdAndGuestFieldsSet() {
        BookingMigrationFixtures.Ids ids = BookingMigrationFixtures.seedBookingGraph(jdbcTemplate);

        assertThatThrownBy(() -> jdbcTemplate.update(INSERT_WITH_IDENTITY,
                UUID.randomUUID(), ids.clientId(), "STAFF", "Олена", "Коваль", "+380501234567", null,
                ids.staffUserId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_appointment_guest_fields");
    }

    @Test
    @DisplayName("a STAFF walk-in header missing guest_name (NULL)")
    void should_rejectStaffHeader_when_guestNameNull() {
        assertStaffRowRejected(null, null, "Коваль", "+380501234567", null);
    }

    /**
     * {@code guest_name IS NOT NULL} is satisfied by the empty string, so the walk-in branch as
     * first written would admit a header with no usable client identity at all — unrecoverable
     * after the fact because a walk-in has no account to recover a name from. Pins the
     * {@code btrim(...) <> ''} guard: mutation-check RED by deleting that clause from V139.
     */
    @Test
    @DisplayName("a STAFF walk-in header whose guest_name is the empty string")
    void should_rejectStaffHeader_when_guestNameBlank() {
        assertStaffRowRejected(null, "", "Коваль", "+380501234567", null);
    }

    /**
     * Whitespace-only, distinct from the empty-string case: a guard written as {@code <> ''}
     * without {@code btrim} would pass this one while failing the empty-string case.
     */
    @Test
    @DisplayName("a STAFF walk-in header whose guest_name is whitespace only")
    void should_rejectStaffHeader_when_guestNameWhitespaceOnly() {
        assertStaffRowRejected(null, "   ", "Коваль", "+380501234567", null);
    }

    @Test
    @DisplayName("a STAFF walk-in header missing guest_surname (stricter than the LINK branch)")
    void should_rejectStaffHeader_when_guestSurnameMissing() {
        assertStaffRowRejected(null, "Олена", null, "+380501234567", null);
    }

    @Test
    @DisplayName("a STAFF header with neither a client_id nor any guest identity")
    void should_rejectStaffHeader_when_noIdentityAtAll() {
        assertStaffRowRejected(null, null, null, null, null);
    }

    // ── APP / LINK regression: both branches survived the rewrite verbatim ─────

    @Test
    @DisplayName("an APP header (client_id set, all guest fields null) is still accepted")
    void should_stillAcceptAppHeader_when_migrationApplied() {
        BookingMigrationFixtures.Ids ids = BookingMigrationFixtures.seedBookingGraph(jdbcTemplate);
        UUID appointmentId = UUID.randomUUID();

        jdbcTemplate.update(INSERT_WITH_IDENTITY,
                appointmentId, ids.clientId(), "APP", null, null, null, null, null);

        assertThat(sourceOf(appointmentId)).isEqualTo("APP");
    }

    @Test
    @DisplayName("a LINK header with guest identity + cancel_token is still accepted")
    void should_stillAcceptLinkHeader_when_migrationApplied() {
        UUID appointmentId = UUID.randomUUID();

        jdbcTemplate.update(INSERT_WITH_IDENTITY,
                appointmentId, null, "LINK", "Олена", "Коваль", "+380501234567", UUID.randomUUID(), null);

        assertThat(sourceOf(appointmentId)).isEqualTo("LINK");
    }

    /**
     * V91's relaxation (carried through V126, and now V139): a terminal LINK header may have a
     * NULL cancel_token, because the guest-cancel UPDATE nulls the token while setting
     * status = CANCELLED. Copying the unrelaxed predicate into V139 would 500 every real guest
     * visit cancellation — this test is the tripwire for that.
     */
    @Test
    @DisplayName("V91/V126 relaxation intact: a CANCELLED LINK header may carry a NULL cancel_token")
    void should_stillAcceptLinkHeader_when_cancelledWithNullCancelToken() {
        UUID appointmentId = UUID.randomUUID();

        jdbcTemplate.update("""
                INSERT INTO appointments (id, client_id, status, booking_source,
                    guest_name, guest_surname, guest_phone, cancel_token, created_at, updated_at)
                VALUES (?, NULL, 'CANCELLED', 'LINK', 'Олена', 'Коваль', '+380501234567', NULL, NOW(), NOW())
                """, appointmentId);

        assertThat(sourceOf(appointmentId)).isEqualTo("LINK");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private UUID insertStaffWalkIn(UUID createdByUserId) {
        UUID appointmentId = UUID.randomUUID();
        jdbcTemplate.update(INSERT_WITH_IDENTITY,
                appointmentId, null, "STAFF", "Олена", "Коваль", "+380501234567", null, createdByUserId);
        return appointmentId;
    }

    /**
     * Asserts the row is rejected <em>by {@code chk_appointment_guest_fields} specifically</em>.
     * Naming the constraint is the whole value of this helper: {@code appointments} has several
     * other rejecters raising the identical exception type, so an {@code isInstanceOf}-only
     * assertion would stay green even if the STAFF branch it is probing were deleted outright.
     */
    private void assertStaffRowRejected(
            UUID clientId, String guestName, String guestSurname, String guestPhone, UUID cancelToken) {
        BookingMigrationFixtures.Ids ids = BookingMigrationFixtures.seedBookingGraph(jdbcTemplate);

        assertThatThrownBy(() -> jdbcTemplate.update(INSERT_WITH_IDENTITY,
                UUID.randomUUID(), clientId, "STAFF", guestName, guestSurname, guestPhone, cancelToken,
                ids.staffUserId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_appointment_guest_fields");
    }

    private String sourceOf(UUID appointmentId) {
        return jdbcTemplate.queryForObject(
                "SELECT booking_source FROM appointments WHERE id = ?", String.class, appointmentId);
    }
}
