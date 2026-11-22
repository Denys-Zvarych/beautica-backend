package com.beautica.client.provider.service;

import com.beautica.booking.enums.BookingStatus;
import com.beautica.client.provider.dto.ClientCardResponse;
import com.beautica.client.provider.dto.ClientKind;
import com.beautica.client.provider.dto.ClientListItemResponse;
import com.beautica.client.provider.dto.ClientOwnRatingResponse;
import com.beautica.client.provider.repository.ClientOwnRatingProjection;
import com.beautica.common.RatingBucket;
import com.beautica.review.service.ClientRatingSummaryService;
import com.beautica.client.provider.dto.ClientVisitResponse;
import com.beautica.client.provider.repository.ClientCardRowProjection;
import com.beautica.client.provider.repository.ClientListRowProjection;
import com.beautica.client.provider.repository.ClientVisitItemProjection;
import com.beautica.client.provider.repository.ClientVisitPageRowProjection;
import com.beautica.client.provider.repository.ProviderClientRepository;
import com.beautica.client.provider.service.ClientScope.MasterScope;
import com.beautica.client.provider.service.ClientRef.GuestAnchor;
import com.beautica.client.provider.service.ClientRef.Registered;
import com.beautica.client.provider.service.ClientScope.SalonScope;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.security.PhoneHasher;
import com.beautica.common.security.PhoneHasher.HashedPhone;
import com.beautica.moderation.service.ClientBlockService;
import com.beautica.moderation.service.ClientBlockService.BlockedAmong;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final ClientPhonePolicy phonePolicy;
    private final ClientRatingSummaryService ratingSummaryService;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    private static final TypeReference<List<ServedMasterJson>> SERVED_MASTERS_TYPE = new TypeReference<>() {
    };

    /** CLI-73 — one element of the card statement's {@code servedByMasters} JSON column. */
    record ServedMasterJson(UUID masterId, String firstName, String lastName, String avatarUrl) {
    }

    /** The row as the service reads it; {@code guestPhone} never leaves this class. */
    private record ClientRow(
            UUID clientId, String guestPhone, Instant lastVisitAt, Instant nextVisitAt,
            long completedVisits, UUID anchorBookingId, String guestName, String guestSurname, String firstName,
            String lastName, String avatarUrl, BigDecimal avgRating, int reviewCount, String verifiedPhone) {

        static ClientRow of(ClientListRowProjection p) {
            return new ClientRow(p.getClientId(), p.getGuestPhone(), p.getLastVisitAt(), p.getNextVisitAt(),
                    p.getCompletedVisits() == null ? 0 : p.getCompletedVisits(), p.getAnchorBookingId(),
                    p.getGuestName(), p.getGuestSurname(), p.getFirstName(), p.getLastName(), p.getAvatarUrl(),
                    p.getAvgRating(), p.getReviewCount() == null ? 0 : p.getReviewCount(), p.getVerifiedPhone());
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
                // B527 D7: the account's verified-phone hash also blocks the person (one person, one answer).
                phone = row.verifiedPhone() == null ? null : phoneHasher.tryHash(row.verifiedPhone()).orElse(null);
                if (phone != null) {
                    phones.add(phone);
                }
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
            boolean phoneBlocked = phone != null && blocked.isPhoneBlocked(phone);
            boolean isBlocked = row.registered() ? blocked.isUserBlocked(row.clientId()) || phoneBlocked : phoneBlocked;
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

    // ── B508: client card + visit history (CLI-42..45) ───────────────────────

    /**
     * One aggregate statement + one block lookup (the guest phone is read inside the aggregate from the anchor booking).
     * Unknown / out-of-scope / anonymised / registered-anchor all produce zero visits in scope and the same
     * {@link NotFoundException} (D6) — never a 403, so ids cannot be probed across salons.
     */
    @Transactional(readOnly = true)
    public ClientCardResponse getCard(ClientScope scope, ClientRef ref) {
        ClientCardRowProjection row = loadCard(scope, ref, OffsetDateTime.now(clock));
        long total = row == null || row.getTotalVisits() == null ? 0 : row.getTotalVisits();
        if (total == 0) {
            throw clientNotFound();
        }
        ClientCardResponse.Stats stats = new ClientCardResponse.Stats((int) total,
                toInt(row.getCompletedVisits()), toInt(row.getUpcomingVisits()));
        List<ClientCardResponse.ServedMaster> servedBy = parseServedByMasters(row.getServedByMasters());
        return switch (ref) {
            case Registered(UUID userId) -> {
                // B527 D7: account row OR the hash of the account's verified phone (one blockedAmong statement).
                HashedPhone verified = row.getVerifiedPhone() == null
                        ? null : phoneHasher.tryHash(row.getVerifiedPhone()).orElse(null);
                BlockedAmong among = clientBlockService.blockedAmong(scope.blockScope(), List.of(userId),
                        verified == null ? List.of() : List.of(verified));
                boolean blocked = among.isUserBlocked(userId) || (verified != null && among.isPhoneBlocked(verified));
                int ratingCount = row.getReviewCount() == null ? 0 : row.getReviewCount();
                // B526: distribution = one grouped query, only when there is something to count (D2 shared compute)
                List<RatingBucket> distribution = ratingCount > 0
                        ? ratingSummaryService.distributionFor(userId)
                        : ClientRatingSummaryService.zeroDistribution();
                yield new ClientCardResponse(ClientKind.REGISTERED, userId, null,
                        joinName(row.getFirstName(), row.getLastName()), row.getAvatarUrl(),
                        phonePolicy.phoneFor(row.getVerifiedPhone()), blocked,
                        ratingCount > 0 ? row.getAvgRating() : null, ratingCount, distribution,
                        utc(row.getFirstVisitAt()),
                        utc(row.getLastVisitAt()), utc(row.getNextVisitAt()), stats, servedBy);
            }
            case GuestAnchor ignored -> {
                String guestPhone = row.getGuestPhone();
                HashedPhone hashed = guestPhone == null ? null : phoneHasher.tryHash(guestPhone).orElse(null);
                boolean blocked = hashed != null && clientBlockService
                        .blockedAmong(scope.blockScope(), List.of(), List.of(hashed)).isPhoneBlocked(hashed);
                yield new ClientCardResponse(ClientKind.GUEST, null, row.getAnchorBookingId(),
                        joinName(row.getGuestName(), row.getGuestSurname()), null,
                        phonePolicy.phoneFor(guestPhone), blocked, null, 0,
                        ClientRatingSummaryService.zeroDistribution(),
                        utc(row.getFirstVisitAt()), utc(row.getLastVisitAt()), utc(row.getNextVisitAt()), stats,
                        servedBy);
            }
        };
    }

    /**
     * Newest visit first; two statements whatever the page size or the items per visit: the page of visit keys (with
     * the total) and every row of those visits in scope, assembled here. A client with no visit in scope is the same
     * 404 as the card.
     *
     * @param pageable page + size only; the order is fixed
     */
    @Transactional(readOnly = true)
    public Page<ClientVisitResponse> getVisits(ClientScope scope, ClientRef ref, Pageable pageable) {
        List<ClientVisitPageRowProjection> pageRows = loadVisitPage(scope, ref, pageable.getPageSize(),
                pageable.getOffset());
        long total = pageRows.isEmpty() || pageRows.get(0).getTotal() == null ? 0 : pageRows.get(0).getTotal();
        if (total == 0) {
            throw clientNotFound();
        }
        List<UUID> keys = pageRows.stream().map(ClientVisitPageRowProjection::getVisitKey)
                .filter(java.util.Objects::nonNull).toList();
        if (keys.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, total);
        }
        Map<UUID, List<ClientVisitItemProjection>> itemsByVisit = new LinkedHashMap<>();
        for (ClientVisitItemProjection item : loadVisitItems(scope, keys)) {
            itemsByVisit.computeIfAbsent(item.getVisitKey(), k -> new ArrayList<>()).add(item);
        }
        List<ClientVisitResponse> visits = new ArrayList<>(keys.size());
        for (ClientVisitPageRowProjection page : pageRows) {
            List<ClientVisitItemProjection> items = itemsByVisit.getOrDefault(page.getVisitKey(), List.of());
            visits.add(new ClientVisitResponse(page.getVisitKey(), utc(page.getStartsAt()),
                    items.isEmpty() ? null : items.get(0).getSalonId(),
                    items.stream().map(ProviderClientService::toItem).toList()));
        }
        return new PageImpl<>(visits, pageable, total);
    }

    /**
     * B526 (CLI-59) — the viewer's own visible ratings of a registered client, newest visit first. A cheap EXISTS
     * runs first (identical 404 for unknown / out-of-scope / anonymised, D6, same scope + anchor rules as the card);
     * a guest is never rateable (REV-D04), so a resolved guest always gets an empty page with no further statement.
     * Registered: exists, page, count — three statements whatever the page size.
     */
    @Transactional(readOnly = true)
    public Page<ClientOwnRatingResponse> getOwnRatings(ClientScope scope, ClientRef ref, Pageable pageable) {
        if (!clientInScope(scope, ref)) {
            throw clientNotFound();
        }
        if (!(ref instanceof Registered(UUID userId))) {
            return new PageImpl<>(List.of(), pageable, 0);
        }
        int limit = pageable.getPageSize();
        long offset = pageable.getOffset();
        List<ClientOwnRatingProjection> rows;
        long total;
        switch (scope) {
            case SalonScope(UUID salonId, UUID masterId) when masterId == null -> {
                rows = repository.ratingsBySalon(salonId, userId, limit, offset);
                total = repository.countRatingsBySalon(salonId, userId);
            }
            case SalonScope(UUID salonId, UUID masterId) -> {
                rows = repository.ratingsBySalonAndMaster(salonId, masterId, userId, limit, offset);
                total = repository.countRatingsBySalonAndMaster(salonId, masterId, userId);
            }
            case MasterScope(UUID masterId, UUID ignoredSalonId) -> {
                rows = repository.ratingsByMaster(masterId, userId, limit, offset);
                total = repository.countRatingsByMaster(masterId, userId);
            }
        }
        List<ClientOwnRatingResponse> items = rows.stream()
                .map(p -> new ClientOwnRatingResponse(p.getBookingId(), utc(p.getVisitAt()), p.getServiceName(),
                        p.getStars()))
                .toList();
        return new PageImpl<>(items, pageable, total);
    }

    private boolean clientInScope(ClientScope scope, ClientRef ref) {
        return switch (ref) {
            case Registered(UUID userId) -> switch (scope) {
                case SalonScope(UUID salonId, UUID masterId) when masterId == null ->
                        repository.existsUserBySalon(salonId, userId);
                case SalonScope(UUID salonId, UUID masterId) ->
                        repository.existsUserBySalonAndMaster(salonId, masterId, userId);
                case MasterScope(UUID masterId, UUID ignoredSalonId) -> repository.existsUserByMaster(masterId, userId);
            };
            case GuestAnchor(UUID bookingId) -> switch (scope) {
                case SalonScope(UUID salonId, UUID masterId) when masterId == null ->
                        repository.existsGuestBySalon(salonId, bookingId);
                case SalonScope(UUID salonId, UUID masterId) ->
                        repository.existsGuestBySalonAndMaster(salonId, masterId, bookingId);
                case MasterScope(UUID masterId, UUID ignoredSalonId) ->
                        repository.existsGuestByMaster(masterId, bookingId);
            };
        };
    }

    private ClientCardRowProjection loadCard(ClientScope scope, ClientRef ref, OffsetDateTime now) {
        return switch (ref) {
            case Registered(UUID userId) -> switch (scope) {
                case SalonScope(UUID salonId, UUID masterId) when masterId == null ->
                        repository.cardUserBySalon(salonId, userId, now);
                case SalonScope(UUID salonId, UUID masterId) ->
                        repository.cardUserBySalonAndMaster(salonId, masterId, userId, now);
                case MasterScope(UUID masterId, UUID ignoredSalonId) ->
                        repository.cardUserByMaster(masterId, userId, now);
            };
            case GuestAnchor(UUID bookingId) -> switch (scope) {
                case SalonScope(UUID salonId, UUID masterId) when masterId == null ->
                        repository.cardGuestBySalon(salonId, bookingId, now);
                case SalonScope(UUID salonId, UUID masterId) ->
                        repository.cardGuestBySalonAndMaster(salonId, masterId, bookingId, now);
                case MasterScope(UUID masterId, UUID ignoredSalonId) ->
                        repository.cardGuestByMaster(masterId, bookingId, now);
            };
        };
    }

    /**
     * CLI-73 — the master chips, read from the card statement's own JSON column (salon scope only, salon-wide whatever
     * {@code masterId}); {@code null} — the independent-master and SALON_MASTER cards — is no chips.
     */
    private List<ClientCardResponse.ServedMaster> parseServedByMasters(@Nullable String json) {
        if (json == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, SERVED_MASTERS_TYPE).stream()
                    .map(m -> new ClientCardResponse.ServedMaster(m.masterId(),
                            joinName(m.firstName(), m.lastName()), m.avatarUrl()))
                    .toList();
        } catch (JsonProcessingException e) {
            // The payload carries staff names: name the failure type only.
            throw new IllegalStateException("Malformed servedByMasters column: " + e.getClass().getSimpleName());
        }
    }

    private List<ClientVisitPageRowProjection> loadVisitPage(ClientScope scope, ClientRef ref, int limit,
                                                             long offset) {
        return switch (ref) {
            case Registered(UUID userId) -> switch (scope) {
                case SalonScope(UUID salonId, UUID masterId) when masterId == null ->
                        repository.visitsUserBySalon(salonId, userId, limit, offset);
                case SalonScope(UUID salonId, UUID masterId) ->
                        repository.visitsUserBySalonAndMaster(salonId, masterId, userId, limit, offset);
                case MasterScope(UUID masterId, UUID ignoredSalonId) ->
                        repository.visitsUserByMaster(masterId, userId, limit, offset);
            };
            case GuestAnchor(UUID bookingId) -> switch (scope) {
                case SalonScope(UUID salonId, UUID masterId) when masterId == null ->
                        repository.visitsGuestBySalon(salonId, bookingId, limit, offset);
                case SalonScope(UUID salonId, UUID masterId) ->
                        repository.visitsGuestBySalonAndMaster(salonId, masterId, bookingId, limit, offset);
                case MasterScope(UUID masterId, UUID ignoredSalonId) ->
                        repository.visitsGuestByMaster(masterId, bookingId, limit, offset);
            };
        };
    }

    private List<ClientVisitItemProjection> loadVisitItems(ClientScope scope, List<UUID> keys) {
        return switch (scope) {
            case SalonScope(UUID salonId, UUID masterId) when masterId == null ->
                    repository.visitItemsBySalon(salonId, keys);
            case SalonScope(UUID salonId, UUID masterId) ->
                    repository.visitItemsBySalonAndMaster(salonId, masterId, keys);
            case MasterScope(UUID masterId, UUID ignoredSalonId) -> repository.visitItemsByMaster(masterId, keys);
        };
    }

    private static ClientVisitResponse.Item toItem(ClientVisitItemProjection p) {
        return new ClientVisitResponse.Item(p.getBookingId(), p.getServiceName(), BookingStatus.valueOf(p.getStatus()),
                utc(p.getStartsAt()), p.getMasterId(), joinName(p.getMasterFirstName(), p.getMasterLastName()),
                p.getPriceAtBooking(), p.getPriceMaxAtBooking());
    }

    private static int toInt(@Nullable Long value) {
        return value == null ? 0 : Math.toIntExact(value);
    }

    /** One identical 404 for every "no such client in your scope" cause (D6); the message is never echoed. */
    private static NotFoundException clientNotFound() {
        return new NotFoundException("CLIENT_NOT_FOUND");
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
