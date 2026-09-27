package com.beautica.salon.service;

/**
 * Which operation ordered a call into {@link StaffAccountDisposalService#dispose}. Phase 301's
 * self-delete caller exposed a pre-existing defect: the disposal audit log hardcoded "Salon
 * deletion" for all four callers, so a self-delete (or an owner-initiated
 * {@code removeMaster}/{@code removeAdmin}) rendered a log line describing a salon teardown that
 * never happened.
 *
 * <p>An enum rather than a free-form {@code String} deliberately, so the four call sites are
 * exhaustive and a fifth caller cannot invent its own wording — see
 * {@link StaffAccountDisposalService#dispose}'s javadoc for why this stays a single shared seam.
 */
public enum StaffDisposalReason {

    /** {@code SalonService#deleteSalonStaff} — the whole salon is being torn down. */
    SALON_DELETION("Salon deletion"),

    /** {@code SalonService#removeMaster} — the owner removed one master from their salon. */
    MASTER_REMOVAL("Master removal"),

    /** {@code SalonService#removeAdmin} — the owner (or another admin) removed one admin. */
    ADMIN_REMOVAL("Admin removal"),

    /**
     * {@code StaffAccountSelfDeletionService#deleteOwnAccount} — the account being disposed of is
     * its own actor. The disposal log omits the {@code by actor} clause on this branch instead of
     * rendering it, since naming the deleted account as the "actor" who ordered its own deletion
     * reads as a third party having done it.
     */
    SELF_DELETE("Self-delete");

    private final String label;

    StaffDisposalReason(String label) {
        this.label = label;
    }

    /** The human-readable prefix rendered into the disposal audit log line. */
    public String label() {
        return label;
    }
}
