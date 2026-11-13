package com.beautica.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Phase 488 — pins for the audit LOWs on the owner self-delete (written expected-red, green since audit-fix
 * cycle 1). Do NOT weaken an assertion to make it pass: fix the production code.
 */
@DisplayName("Owner self-delete — residue pins (Phase 488 audit LOWs)")
class OwnerSelfDeleteResidueExpectedRedIT extends AbstractOwnerSelfDeleteWorldIT {

    @Test
    @DisplayName("LOW-a: the detached salon's owner-written description and location_note are NULL, "
            + "alongside phone and instagram_url")
    void should_blankAllOwnerWrittenSalonText_when_ownerAccountDeleted() throws Exception {
        OldWorld old = buildDeletedSalonWorld();
        Map<String, Object> before = jdbcTemplate.queryForMap(
                "SELECT description, location_note FROM salons WHERE id = ?", old.salonId());
        assertThat(before.get("description")).as("precondition: seeded").isNotNull();
        assertThat(before.get("location_note")).as("precondition: seeded").isNotNull();

        deleteAccount(old);

        Map<String, Object> salon = jdbcTemplate.queryForMap(
                "SELECT owner_id, phone, instagram_url, description, location_note, name FROM salons WHERE id = ?",
                old.salonId());
        assertThat(salon.get("owner_id")).as("detached").isNull();
        assertThat(salon.get("phone")).as("phone blanked").isNull();
        assertThat(salon.get("instagram_url")).as("instagram blanked").isNull();
        assertThat(salon.get("description")).as("owner-written description blanked, actual=%s",
                salon.get("description")).isNull();
        assertThat(salon.get("location_note")).as("owner-written location_note blanked, actual=%s",
                salon.get("location_note")).isNull();
        assertThat(salon.get("name")).as("name kept for client history").isEqualTo(SALON_NAME);
    }

    @Test
    @DisplayName("LOW-b: a still-queued INVITE outbox row for the deleted owner's salon is gone, "
            + "so the drain worker can never mail an invite into a detached salon")
    void should_removeQueuedInviteOutboxRow_when_ownerAccountDeleted() throws Exception {
        OldWorld old = buildDeletedSalonWorld();
        assertThat(csd.count("SELECT COUNT(*) FROM notification_outbox WHERE event_type = 'INVITE' "
                + "AND status = 'PENDING' AND aggregate_id = ?", old.inviteId()))
                .as("precondition: the API invite queued exactly one PENDING outbox row").isEqualTo(1);

        deleteAccount(old);

        assertThat(csd.count("SELECT COUNT(*) FROM invite_tokens WHERE id = ?", old.inviteId()))
                .as("invite token deleted (already green)").isZero();
        assertThat(csd.count("SELECT COUNT(*) FROM notification_outbox WHERE event_type = 'INVITE' "
                + "AND aggregate_id = ? AND status IN ('PENDING', 'PROCESSING')", old.inviteId()))
                .as("no deliverable INVITE outbox row may survive its deleted invite token").isZero();
    }

    @Test
    @DisplayName("LOW-c, perf: the staff-as-client audit (SALON_MASTER/SALON_ADMIN only) issues no "
            + "query for an owner delete")
    void should_runNoStaffClientAuditQuery_when_ownerAccountDeleted() throws Exception {
        OldWorld old = buildDeletedSalonWorld();
        // The salon delete above legitimately runs the salon-scoped audit — only the account delete is under test.
        clearInvocations(staffClientReferenceAuditRepository);

        deleteAccount(old);

        verifyNoInteractions(staffClientReferenceAuditRepository);
    }
}
