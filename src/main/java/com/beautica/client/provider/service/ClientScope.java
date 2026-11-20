package com.beautica.client.provider.service;

import com.beautica.moderation.service.BlockScope;
import org.springframework.lang.Nullable;

import java.util.UUID;

/**
 * B507 — whose bookings a provider's client list covers (CLI-D06). Resolved from the caller by
 * {@link ProviderClientScopeResolver}; the repository has one query per variant.
 */
public sealed interface ClientScope {

    /** The block scope the list's {@code blocked} flag reads (D5): the salon's, else the independent master's. */
    BlockScope blockScope();

    /** Every booking of the salon, optionally narrowed to one of its masters. */
    record SalonScope(UUID salonId, @Nullable UUID masterId) implements ClientScope {

        @Override
        public BlockScope blockScope() {
            return BlockScope.salon(salonId);
        }
    }

    /** The bookings on one master row; {@code salonId} is the master's salon (null for an independent master). */
    record MasterScope(UUID masterId, @Nullable UUID salonId) implements ClientScope {

        @Override
        public BlockScope blockScope() {
            return salonId != null ? BlockScope.salon(salonId) : BlockScope.master(masterId);
        }
    }
}
