package com.beautica.notification.inapp.service;

import com.beautica.auth.Role;
import com.beautica.master.entity.Master;
import com.beautica.salon.entity.Salon;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Resolves the recipient set for one in-app feed event (phase 333) — the single place the
 * event→recipient matrix's role fan-out lives, so every call site in {@link InAppNotificationService}
 * shares ONE definition of "the provider set" instead of five copies drifting independently.
 *
 * <p><b>REUSE-FIRST.</b> {@link #addOwnerAndAdmins} calls {@link
 * UserRepository#findBySalonIdAndRoleAndIsActiveTrue} — the EXACT query already behind the salon
 * «Команда» roster ({@code SalonService#getSalonStaff}) — rather than writing a parallel
 * admin-membership lookup. A performing master's own user account is read directly off the already
 * loaded {@link Master#getUser()} association (never a second query).
 *
 * <p>Every method returns a de-duplicated {@link Set}: a user holding two qualifying roles on one
 * event (the classic "owner who is also the performing {@code SALON_OWNER}-type master" case) lands
 * in the set once, so the caller writes exactly one feed row for them — never two. Actor exclusion is
 * NOT this class's job; every caller in {@link InAppNotificationService} removes the actor from the
 * returned set itself, uniformly, right before writing rows.
 */
@Component
@RequiredArgsConstructor
class InAppRecipientResolver {

    private final UserRepository userRepository;

    /**
     * Owner + all active admins + the performing master — rows 1 (non-walk-in create) and 2 (client
     * cancel) of the matrix, and the "client-initiated" leg of row 4 (reschedule). For an
     * {@code INDEPENDENT_MASTER} booking ({@code salon == null}) this collapses to just the master.
     */
    Set<UUID> providerSet(Master master, Salon salon) {
        Set<UUID> ids = new LinkedHashSet<>();
        addMasterUser(master, ids);
        addOwnerAndAdmins(salon, ids);
        return ids;
    }

    /**
     * Owner + the performing master, deliberately WITHOUT admins — row 8 (review received): "the
     * business owner", not the whole staff roster, per the architect's noise-limiting decision.
     */
    Set<UUID> ownerAndMasterOnly(Master master, Salon salon) {
        Set<UUID> ids = new LinkedHashSet<>();
        addMasterUser(master, ids);
        if (salon != null) {
            ids.add(salon.getOwner().getId());
        }
        return ids;
    }

    /** Owner + all active admins, no master — row 5 (invite accepted). */
    Set<UUID> ownerAndAdmins(Salon salon) {
        Set<UUID> ids = new LinkedHashSet<>();
        addOwnerAndAdmins(salon, ids);
        return ids;
    }

    /**
     * The performing master alone — walk-in creates (row 1w), the "always" leg of reschedule (row
     * 4), and the base of row 3 (decline), which layers the client on top.
     */
    Set<UUID> masterOnly(Master master) {
        Set<UUID> ids = new LinkedHashSet<>();
        addMasterUser(master, ids);
        return ids;
    }

    private void addMasterUser(Master master, Set<UUID> ids) {
        // "A performing master WITHOUT a user account contributes nothing" (matrix rule) — a
        // detached master (hard-deleted staff account, historical stub kept) has master.getUser()
        // == null, exactly like the SMS/display-name null guards elsewhere in this package.
        if (master != null && master.getUser() != null) {
            ids.add(master.getUser().getId());
        }
    }

    private void addOwnerAndAdmins(Salon salon, Set<UUID> ids) {
        if (salon == null) {
            // INDEPENDENT_MASTER booking — no salon, no owner/admin concept.
            return;
        }
        // Salon.owner is @ManyToOne(LAZY) on a NOT NULL column — getId() is served off the
        // uninitialised proxy without a statement (see BookingRepository#findByIdWithFullGraph's own
        // javadoc for the identical, already-verified claim about this exact association).
        ids.add(salon.getOwner().getId());
        for (User admin : userRepository.findBySalonIdAndRoleAndIsActiveTrue(salon.getId(), Role.SALON_ADMIN)) {
            ids.add(admin.getId());
        }
    }
}
