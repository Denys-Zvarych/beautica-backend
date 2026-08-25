package com.beautica.master.entity;

public enum MasterType {
    SALON_MASTER,
    INDEPENDENT_MASTER,
    // A SALON_OWNER who also personally provides services inside their PRIMARY salon.
    // Distinct from SALON_MASTER: the owner is NOT an invited member, sees full client
    // data, may confirm/complete their own bookings, and is authorized via salon ownership
    // (salon.owner.id == user.id). Created automatically when the owner's first (primary)
    // salon is registered. Only one SALON_OWNER-type master row may exist per user.
    SALON_OWNER;

    /**
     * <b>The single source of the "per-role address matrix (locked)"</b>
     * (ARCHITECTURE-backend.md, § "Per-role address matrix").
     *
     * <p>Returns whether a master of this type may have their OWN address
     * ({@code users.street} / {@code users.building_no} / {@code users.location_note}, and the
     * locality cascade ids derived from it) surfaced to a caller who is not that master.
     *
     * <p>Only {@link #INDEPENDENT_MASTER} may: an independent master's address IS the discoverable
     * location clients need to find them. For {@link #SALON_MASTER} / {@link #SALON_OWNER} the
     * working address is the SALON's business address, and {@code users.street} is not even
     * reliably theirs — a multi-salon owner's {@code users} row carries the most recently created
     * salon's address, so echoing it would print salon B's street beside a master working in
     * salon A.
     *
     * <p>Null-tolerant on purpose (a native projection row may carry a {@code NULL}
     * {@code master_type}): unknown fails CLOSED, i.e. the address is suppressed.
     *
     * <p><b>Every surface returning a master's own address must call this</b> rather than
     * re-testing {@code == INDEPENDENT_MASTER} inline — that inline copy is what let
     * {@code GET /favorites/masters} diverge from the locked matrix, and an entirely MISSING
     * check is what let {@code GET /search/masters} gate the address trio on authentication
     * alone. Current callers (THREE — keep this list exact; a stale count is precisely what
     * makes the next reviewer stop looking after the surfaces already named):
     * <ol>
     *   <li>{@link com.beautica.master.dto.MasterDetailResponse#fromPublic} —
     *       {@code GET /masters/{id}} (public master profile)</li>
     *   <li>{@code com.beautica.favorite.service.FavoriteService#mapMasterRow} —
     *       {@code GET /favorites/masters}</li>
     *   <li>{@code com.beautica.search.service.SearchService#mapMasterRow} —
     *       {@code GET /search/masters}</li>
     * </ol>
     *
     * <p>Adding a fourth surface that returns {@code users.street} / {@code users.building_no} /
     * {@code users.location_note} means adding a fourth entry here.
     */
    public static boolean disclosesOwnAddress(MasterType type) {
        return type == INDEPENDENT_MASTER;
    }

    /**
     * Reads a native projection's {@code masters.master_type} column into the enum, failing
     * CLOSED.
     *
     * <p>The column is {@code NOT NULL} and Hibernate hands back the raw {@code varchar}, so the
     * happy path is a plain {@link #valueOf}. Both failure branches — a {@code NULL} column and a
     * value no constant matches (a not-yet-deployed enum value read by an older instance
     * mid-rolling-deploy) — return {@code null}, which {@link #disclosesOwnAddress} treats as
     * "does not disclose". Failing closed is the only safe direction for a masking rule: the cost
     * of a wrong guess is a missing street line, never a leaked one. It also keeps a malformed row
     * from throwing {@link IllegalArgumentException} out of a read endpoint as a 500.
     *
     * <p>Lives here rather than in each mapper so the two native-projection surfaces
     * ({@code FavoriteService} / {@code SearchService}) parse the column identically — the same
     * single-definition reasoning that governs {@link #disclosesOwnAddress} itself.
     *
     * @param rawProjectionValue the JDBC value of the {@code master_type} column
     * @return the parsed constant, or {@code null} when absent or unrecognised
     */
    public static MasterType fromProjection(Object rawProjectionValue) {
        if (!(rawProjectionValue instanceof String value)) {
            return null;
        }
        try {
            return valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
