package com.beautica.master.service;

import com.beautica.common.cache.BookabilitySearchCacheEvictor;
import com.beautica.master.repository.MasterRepository;
import com.beautica.master.repository.MasterRepository.SearchBookabilityRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Clears the discovery search caches only when a write actually flips a master's search membership
 * (perf audit 2026-10-05, finding 1). Schedule and service/assignment writers bracket their mutation:
 * {@link #capture} BEFORE it, {@link #clearSearchCachesIfFlipped} AFTER it, both inside the write
 * transaction. Each call reads the cheap structural verdict search itself lists by
 * ({@link MasterRepository#findSearchBookability} — {@code MasterBookabilitySql}), with the SAME
 * Kyiv "today" so a midnight rollover between the two reads cannot fake a flip.
 *
 * <p>A day-off override, a second working interval, a price-band edit or any write that leaves a
 * master bookable-and-listed (or unlisted) therefore clears nothing; only a master gaining their
 * first schedule/service or losing their last one clears — and only the ONE surface that master is
 * listed on ({@link BookabilitySearchCacheEvictor#clearForMaster}), after commit (§F-2), never
 * before the database shows the change.
 *
 * <p>Cost: two single-statement reads bounded by the caller's master ids, plus a flush the commit
 * would perform anyway.
 */
@Component
@RequiredArgsConstructor
public class MasterSearchVisibilityGuard {

    private final MasterRepository masterRepository;
    private final ScheduleDateMath scheduleDateMath;
    private final BookabilitySearchCacheEvictor bookabilitySearchCacheEvictor;

    /** The pre-write verdicts of a set of masters, read at a pinned Kyiv date. */
    public record Snapshot(LocalDate today, Map<UUID, Verdict> verdicts) {

        static final Snapshot EMPTY = new Snapshot(null, Map.of());

        public Snapshot {
            verdicts = Map.copyOf(verdicts);
        }
    }

    /** One master's structural search verdict and the salon whose surface lists them. */
    public record Verdict(UUID salonId, boolean bookable) {
    }

    /** Reads the current verdict of every master in {@code masterIds}; call BEFORE the write. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Snapshot capture(Collection<UUID> masterIds) {
        if (masterIds.isEmpty()) {
            return Snapshot.EMPTY;
        }
        LocalDate today = scheduleDateMath.today();
        return new Snapshot(today, read(masterIds, today));
    }

    /**
     * Flushes the write, re-reads the verdicts of the masters in {@code before}, and registers an
     * afterCommit clear of each surface a flipped master is listed on. A no-op when nothing flipped.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void clearSearchCachesIfFlipped(Snapshot before) {
        if (before.verdicts().isEmpty()) {
            return;
        }
        masterRepository.flush();
        Surfaces flipped = flippedSurfaces(before.verdicts(), read(before.verdicts().keySet(), before.today()));
        if (flipped.salon() || flipped.master()) {
            clearAfterCommit(flipped);
        }
    }

    private Map<UUID, Verdict> read(Collection<UUID> masterIds, LocalDate today) {
        Map<UUID, Verdict> verdicts = new HashMap<>();
        for (SearchBookabilityRow row : masterRepository.findSearchBookability(masterIds, today)) {
            verdicts.put(row.getMasterId(),
                    new Verdict(row.getSalonId(), Boolean.TRUE.equals(row.getBookable())));
        }
        return verdicts;
    }

    /**
     * Which surfaces list a master whose verdict (or salon) changed: salon search for a
     * salon-attached master, master search for an independent one. A master missing from the
     * after-read (deleted) counts as no longer bookable.
     */
    private static Surfaces flippedSurfaces(Map<UUID, Verdict> before, Map<UUID, Verdict> after) {
        Surfaces surfaces = Surfaces.NONE;
        for (Map.Entry<UUID, Verdict> entry : before.entrySet()) {
            Verdict was = entry.getValue();
            Verdict now = after.getOrDefault(entry.getKey(), new Verdict(was.salonId(), false));
            if (!was.equals(now)) {
                surfaces = surfaces.with(was.salonId()).with(now.salonId());
            }
        }
        return surfaces;
    }

    private void clearAfterCommit(Surfaces surfaces) {
        Runnable clear = () -> {
            if (surfaces.salon()) {
                bookabilitySearchCacheEvictor.clearSalonSearch();
            }
            if (surfaces.master()) {
                bookabilitySearchCacheEvictor.clearMasterSearch();
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            clear.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                clear.run();
            }
        });
    }

    /** The discovery surfaces to clear. */
    private record Surfaces(boolean salon, boolean master) {

        static final Surfaces NONE = new Surfaces(false, false);

        Surfaces with(UUID salonId) {
            return salonId != null ? new Surfaces(true, master) : new Surfaces(salon, true);
        }
    }
}
