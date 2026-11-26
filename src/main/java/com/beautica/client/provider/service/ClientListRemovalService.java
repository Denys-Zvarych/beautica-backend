package com.beautica.client.provider.service;

import com.beautica.client.provider.repository.ClientListRemovalRepository;
import com.beautica.client.provider.service.ClientScope.MasterScope;
import com.beautica.client.provider.service.ClientScope.SalonScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Collection;
import java.util.UUID;

/**
 * B564 — the {@code client_list_removals} writes (CLI-82..85). Trusts its input: {@link ProviderClientService} has
 * already authorised the {@link ClientScope} and resolved an in-scope identity. Key = the list scope (D1): a
 * {@link SalonScope} — with or without its {@code masterId} narrowing — writes the salon row the owner and every admin
 * share; a {@link MasterScope} writes the master's own row. The two never match each other's list.
 *
 * <p>Never touches bookings, ratings or {@code client_blocks} (CLI-84, MOD-40). Guest phones never reach a log line.
 */
@Service
@RequiredArgsConstructor
public class ClientListRemovalService {

    /** One identity of a list row, as the list SQL groups it: an account, or the guest's E.164 booking phone. */
    public sealed interface ListIdentity {
        record User(UUID userId) implements ListIdentity {
        }

        record Guest(String phone) implements ListIdentity {
            @Override
            public String toString() {
                return "Guest[phone=<redacted>]";
            }
        }
    }

    private final ClientListRemovalRepository repository;
    private final Clock clock;

    /** Upsert: a first removal inserts, a re-remove moves {@code removed_at} to now (CLI-83). */
    @Transactional
    public void remove(ClientScope scope, ListIdentity identity, UUID actorId) {
        UUID id = UUID.randomUUID();
        var now = clock.instant();
        switch (scope) {
            case SalonScope(UUID salonId, UUID ignoredMasterId) -> {
                switch (identity) {
                    case ListIdentity.User(UUID userId) -> repository.upsertSalonUser(id, salonId, userId, now, actorId);
                    case ListIdentity.Guest(String phone) -> repository.upsertSalonGuest(id, salonId, phone, now, actorId);
                }
            }
            case MasterScope(UUID masterId, UUID ignoredSalonId) -> {
                switch (identity) {
                    case ListIdentity.User(UUID userId) ->
                            repository.upsertMasterUser(id, masterId, userId, now, actorId);
                    case ListIdentity.Guest(String phone) ->
                            repository.upsertMasterGuest(id, masterId, phone, now, actorId);
                }
            }
        }
    }

    /** Undo (D5): deletes the row; zero rows is not an error. */
    @Transactional
    public void restore(ClientScope scope, ListIdentity identity) {
        switch (scope) {
            case SalonScope(UUID salonId, UUID ignoredMasterId) -> {
                switch (identity) {
                    case ListIdentity.User(UUID userId) -> repository.deleteSalonUser(salonId, userId);
                    case ListIdentity.Guest(String phone) -> repository.deleteSalonGuest(salonId, phone);
                }
            }
            case MasterScope(UUID masterId, UUID ignoredSalonId) -> {
                switch (identity) {
                    case ListIdentity.User(UUID userId) -> repository.deleteMasterUser(masterId, userId);
                    case ListIdentity.Guest(String phone) -> repository.deleteMasterGuest(masterId, phone);
                }
            }
        }
    }

    /**
     * GST-51 (D3) — in the caller's anonymising transaction: drops the removal rows of {@code phones} that have no
     * open guest booking left. Returns the number of rows deleted.
     */
    @Transactional
    public int purgeGuestPhones(Collection<String> phones) {
        if (phones.isEmpty()) {
            return 0;
        }
        return repository.deleteGuestPhonesWithoutOpenBookings(phones);
    }
}
