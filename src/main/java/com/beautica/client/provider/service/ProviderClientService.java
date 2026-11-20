package com.beautica.client.provider.service;

import com.beautica.client.provider.dto.ClientKind;
import com.beautica.client.provider.dto.ClientListItemResponse;
import com.beautica.client.provider.repository.ClientListRowProjection;
import com.beautica.client.provider.repository.ProviderClientRepository;
import com.beautica.client.provider.service.ClientScope.MasterScope;
import com.beautica.client.provider.service.ClientScope.SalonScope;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.security.PhoneHasher;
import com.beautica.common.security.PhoneHasher.HashedPhone;
import com.beautica.moderation.service.ClientBlockService;
import com.beautica.moderation.service.ClientBlockService.BlockedAmong;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * B507 — the provider's client list (CLI-38..41): one page statement, one count statement and one block statement
 * whatever the page size. Authorisation is the caller's ({@code @PreAuthorize} + {@link ProviderClientScopeResolver});
 * this class trusts the {@link ClientScope}. The guest phone lives only in {@link ClientRow} (redacted toString) long
 * enough to be hashed for the block lookup — it is never copied into the response, a log line or an exception.
 */
@Service
@RequiredArgsConstructor
public class ProviderClientService {

    static final int MIN_QUERY_LENGTH = 2;
    static final int MAX_QUERY_LENGTH = 50;

    private final ProviderClientRepository repository;
    private final ClientBlockService clientBlockService;
    private final PhoneHasher phoneHasher;
    private final Clock clock;

    /** The row as the service reads it; {@code guestPhone} never leaves this class. */
    private record ClientRow(
            UUID clientId, String guestPhone, Instant lastVisitAt, Instant nextVisitAt,
            long completedVisits, UUID anchorBookingId, String guestName, String guestSurname, String firstName,
            String lastName, String avatarUrl, BigDecimal avgRating, int reviewCount) {

        static ClientRow of(ClientListRowProjection p) {
            return new ClientRow(p.getClientId(), p.getGuestPhone(), p.getLastVisitAt(), p.getNextVisitAt(),
                    p.getCompletedVisits() == null ? 0 : p.getCompletedVisits(), p.getAnchorBookingId(),
                    p.getGuestName(), p.getGuestSurname(), p.getFirstName(), p.getLastName(), p.getAvatarUrl(),
                    p.getAvgRating(), p.getReviewCount() == null ? 0 : p.getReviewCount());
        }

        boolean registered() {
            return clientId != null;
        }

        @Override
        public String toString() {
            return "ClientRow[kind=" + (registered() ? "REGISTERED" : "GUEST") + "]";
        }
    }

    /**
     * @param q raw search text (null = no filter); trimmed, 2-50 chars, no control characters, else 400
     * @param pageable page + size only; any sort is ignored (the order is fixed: latest activity first)
     */
    @Transactional(readOnly = true)
    public Page<ClientListItemResponse> listClients(ClientScope scope, @Nullable String q, Pageable pageable) {
        String escapedQuery = normaliseQuery(q);
        OffsetDateTime now = OffsetDateTime.now(clock);
        int limit = pageable.getPageSize();
        long offset = pageable.getOffset();

        List<ClientRow> rows;
        long total;
        switch (scope) {
            case SalonScope(UUID salonId, UUID masterId) when masterId == null -> {
                rows = repository.pageBySalon(salonId, now, escapedQuery, limit, offset)
                        .stream().map(ClientRow::of).toList();
                total = repository.countBySalon(salonId, now, escapedQuery);
            }
            case SalonScope(UUID salonId, UUID masterId) -> {
                rows = repository.pageBySalonAndMaster(salonId, masterId, now, escapedQuery, limit, offset)
                        .stream().map(ClientRow::of).toList();
                total = repository.countBySalonAndMaster(salonId, masterId, now, escapedQuery);
            }
            case MasterScope(UUID masterId, UUID ignoredSalonId) -> {
                rows = repository.pageByMaster(masterId, now, escapedQuery, limit, offset)
                        .stream().map(ClientRow::of).toList();
                total = repository.countByMaster(masterId, now, escapedQuery);
            }
        }
        return new PageImpl<>(toResponses(scope, rows), pageable, total);
    }

    private List<ClientListItemResponse> toResponses(ClientScope scope, List<ClientRow> rows) {
        List<UUID> userIds = new ArrayList<>();
        List<HashedPhone> phones = new ArrayList<>();
        List<HashedPhone> rowPhones = new ArrayList<>(rows.size());
        for (ClientRow row : rows) {
            HashedPhone phone = null;
            if (row.registered()) {
                userIds.add(row.clientId());
            } else if (row.guestPhone() != null) {
                phone = phoneHasher.tryHash(row.guestPhone()).orElse(null);
                if (phone != null) {
                    phones.add(phone);
                }
            }
            rowPhones.add(phone);
        }
        BlockedAmong blocked = clientBlockService.blockedAmong(scope.blockScope(), userIds, phones);
        List<ClientListItemResponse> out = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            ClientRow row = rows.get(i);
            HashedPhone phone = rowPhones.get(i);
            boolean isBlocked = row.registered()
                    ? blocked.isUserBlocked(row.clientId())
                    : phone != null && blocked.isPhoneBlocked(phone);
            out.add(toResponse(row, isBlocked));
        }
        return out;
    }

    private static ClientListItemResponse toResponse(ClientRow row, boolean blocked) {
        if (row.registered()) {
            int ratingCount = row.reviewCount();
            return new ClientListItemResponse(ClientKind.REGISTERED, row.clientId(), null,
                    joinName(row.firstName(), row.lastName()), row.avatarUrl(), utc(row.lastVisitAt()),
                    utc(row.nextVisitAt()), (int) row.completedVisits(),
                    ratingCount > 0 ? row.avgRating() : null, ratingCount, blocked);
        }
        return new ClientListItemResponse(ClientKind.GUEST, null, row.anchorBookingId(),
                joinName(row.guestName(), row.guestSurname()), null, utc(row.lastVisitAt()), utc(row.nextVisitAt()),
                (int) row.completedVisits(), null, 0, blocked);
    }

    private static OffsetDateTime utc(@Nullable Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    /** First + last name; blank parts dropped; null when nothing is left (the app renders «Гість»). */
    private static String joinName(@Nullable String first, @Nullable String last) {
        String f = first == null ? "" : first.strip();
        String l = last == null ? "" : last.strip();
        String joined = (f + " " + l).strip();
        return joined.isEmpty() ? null : joined;
    }

    /**
     * Trim, bound the length (2-50) and reject control characters, then escape the LIKE metacharacters
     * ({@code \}, {@code %}, {@code _}) so the text is matched literally. Null stays null (no filter).
     */
    static String normaliseQuery(@Nullable String q) {
        if (q == null) {
            return null;
        }
        String trimmed = q.strip();
        if (trimmed.length() < MIN_QUERY_LENGTH || trimmed.length() > MAX_QUERY_LENGTH
                || trimmed.chars().anyMatch(Character::isISOControl)) {
            throw new BusinessException("Invalid search text");
        }
        return trimmed.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
