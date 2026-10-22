package com.beautica.booking.service;

import com.beautica.booking.dto.BookingSlugInfoResponse;
import com.beautica.booking.dto.BookingSlugSalonRef;
import com.beautica.booking.dto.CategoryRef;
import com.beautica.booking.dto.ServiceSummaryDto;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.master.entity.Master;
import com.beautica.master.repository.MasterRepository;
import com.beautica.master.service.ScheduleDateMath;
import com.beautica.media.dto.MediaFileResponse;
import com.beautica.media.entity.EntityType;
import com.beautica.media.service.MediaService;
import com.beautica.review.dto.ReviewResponse;
import com.beautica.review.dto.SalonReviewResponse;
import com.beautica.review.dto.SalonReviewSort;

import com.beautica.review.service.ReviewService;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.salon.repository.SalonRepository.SalonBookingLinkRow;
import com.beautica.service.repository.MasterServiceRepository;
import com.beautica.master.entity.MasterType;
import com.beautica.salon.entity.Salon;
import com.beautica.service.entity.CatalogCategory;
import com.beautica.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Generates, persists, and resolves the public per-master booking slug behind
 * {@code beautica.app/book/{slug}} (Phase 13.1).
 *
 * <p><b>Single-table model.</b> The phase doc assumed two physical tables
 * ({@code masters} + {@code independent_masters}) with {@code Long} PKs. The
 * actual schema has one {@code masters} table (UUID PK); independent masters are
 * {@code masters} rows with {@code master_type = 'INDEPENDENT_MASTER'}. So
 * uniqueness, lookup, and lazy backfill all operate over the single
 * {@link MasterRepository}, which already spans every master type.
 *
 * <p><b>Slug format.</b> KMU-2010 Ukrainian-to-Latin transliteration of the
 * master's "first last" name, lowercased, hyphenated, then {@code '-'} + 4
 * {@link SecureRandom} {@code [a-z0-9]} chars. Matches the DB CHECK
 * {@code ^[a-z0-9][a-z0-9\-]*[a-z0-9]$} and is {@code <= 60} chars. Global
 * uniqueness is enforced by a bounded retry loop against
 * {@link MasterRepository#existsByBookingSlug}.
 *
 * <p><b>Salons (phase 376).</b> The same generator issues salon slugs
 * ({@code beautica.app/book/s/{slug}}) from the salon name, probed against
 * {@link SalonRepository#existsByBookingSlug} — a separate namespace, so a salon slug may
 * equal a master slug. Master generation is byte-identical to before the refactor.
 */
@Service
@RequiredArgsConstructor
public class BookingSlugService {

    private static final int RANDOM_SUFFIX_LENGTH = 4;
    private static final int MAX_GENERATION_ATTEMPTS = 5;
    private static final int MAX_SLUG_LENGTH = 60;
    /** Reserve room for the "-XXXX" random suffix so the whole slug fits in 60 chars. */
    private static final int MAX_BASE_LENGTH = MAX_SLUG_LENGTH - (RANDOM_SUFFIX_LENGTH + 1);
    private static final String FALLBACK_BASE = "master";
    /** Phase 376 — base used when a salon name transliterates to fewer than 2 slug chars. */
    private static final String SALON_FALLBACK_BASE = "salon";
    private static final char[] SLUG_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    /** Public master menu is tiny; cap the unbounded list read defensively (Anti-Bug §E-3). */
    private static final int MAX_SERVICES = 100;
    /** Phase 380 D2 — portfolio photos / newest reviews carried on the public master page. */
    static final int PORTFOLIO_LIMIT = 12;
    static final int LATEST_REVIEWS_LIMIT = 5;

    /** KMU-2010 Ukrainian → Latin transliteration (matches the project SlugGenerator map). */
    private static final Map<Character, String> UK_TO_LATIN = Map.ofEntries(
            Map.entry('а', "a"), Map.entry('б', "b"), Map.entry('в', "v"), Map.entry('г', "h"),
            Map.entry('ґ', "g"), Map.entry('д', "d"), Map.entry('е', "e"), Map.entry('є', "ie"),
            Map.entry('ж', "zh"), Map.entry('з', "z"), Map.entry('и', "y"), Map.entry('і', "i"),
            Map.entry('ї', "i"), Map.entry('й', "i"), Map.entry('к', "k"), Map.entry('л', "l"),
            Map.entry('м', "m"), Map.entry('н', "n"), Map.entry('о', "o"), Map.entry('п', "p"),
            Map.entry('р', "r"), Map.entry('с', "s"), Map.entry('т', "t"), Map.entry('у', "u"),
            Map.entry('ф', "f"), Map.entry('х', "kh"), Map.entry('ц', "ts"), Map.entry('ч', "ch"),
            Map.entry('ш', "sh"), Map.entry('щ', "shch"), Map.entry('ь', ""), Map.entry('ю', "iu"),
            Map.entry('я', "ia"));

    private final MasterRepository masterRepository;
    private final MasterServiceRepository masterServiceRepository;
    private final SalonRepository salonRepository;
    private final MediaService mediaService;
    private final ReviewService reviewService;
    private final ScheduleDateMath scheduleDateMath;
    private final SecureRandom random = new SecureRandom();

    /**
     * Returns the master's existing booking slug, or lazily generates, persists,
     * and returns a fresh unique one when the column is still {@code null}.
     * Called from the master-creation paths in {@code MasterService} and idempotent
     * thereafter. Must run inside a transaction: the entity is loaded here via
     * {@code findById}, so it is managed for the duration of this method and Hibernate
     * dirty-checking flushes the new slug on commit — no explicit {@code save()} needed.
     */
    @Transactional
    public String getOrCreateSlug(UUID masterId) {
        Master master = masterRepository.findById(masterId)
                .orElseThrow(() -> new NotFoundException("Master not found"));
        if (master.getBookingSlug() != null) {
            return master.getBookingSlug();
        }
        String slug = generateSlug(master.getUser().getFirstName(), master.getUser().getLastName());
        // No explicit save(): master is managed (findById, same transaction), so the slug
        // assignment is flushed by dirty-checking on commit.
        master.setBookingSlug(slug);
        return slug;
    }

    /**
     * Phase 375 — race-safe sibling of {@link #getOrCreateSlug} for the lazy issue on
     * {@code GET /booking-links/mine}, where two devices of one legacy ({@code booking_slug NULL})
     * master can call concurrently. Returns the existing slug, or generates a candidate and writes it
     * with a conditional {@code UPDATE ... WHERE booking_slug IS NULL}; when that matches no row a
     * concurrent call won, and the committed slug is re-read and returned — so every caller observes
     * the one permanent slug (D4).
     *
     * <p>{@link #getOrCreateSlug} is deliberately left as is: its callers are the master-creation
     * paths, where the row is brand new and cannot be raced.
     *
     * <p><b>Transaction scope (375 audit F1/F2).</b> This short transaction holds only the
     * candidate's existence check, the conditional {@code UPDATE} and — on a lost race — the re-read,
     * so the row lock the {@code UPDATE} takes is released at this method's commit. Callers must NOT
     * wrap it in an outer transaction: a unique-index violation on the {@code UPDATE} marks the
     * transaction rollback-only, and the caller's single retry with a fresh candidate
     * ({@code BookingLinkService#issueSlug}) is only sound because it starts a new transaction.
     *
     * @param master an active, user-attached master with {@code user} fetched (may be detached)
     */
    @Transactional
    public String getOrIssueSlugRaceSafe(Master master) {
        if (master.getBookingSlug() != null) {
            return master.getBookingSlug();
        }
        String candidate = generateSlug(master.displayFirstName(), master.displayLastName());
        if (masterRepository.assignBookingSlugIfAbsent(master.getId(), candidate) == 1) {
            return candidate;
        }
        return masterRepository.findBookingSlugById(master.getId())
                .orElseThrow(() -> new NotFoundException("Master not found"));
    }

    /**
     * Builds a globally-unique slug from the given name. Transliterates, normalises,
     * then appends {@code '-'} + 4 random {@code [a-z0-9]} chars; retries up to
     * {@value #MAX_GENERATION_ATTEMPTS} times on a uniqueness collision before failing.
     * The 4-char random suffix gives ~1.7M combinations per base, so 5 attempts
     * colliding is effectively impossible in practice.
     */
    public String generateSlug(String firstName, String lastName) {
        return generateSlug(composeName(firstName, lastName), FALLBACK_BASE, masterRepository::existsByBookingSlug);
    }

    /**
     * Phase 376 — a globally-unique SALON slug from the salon name: the same generator as masters
     * ({@link #generateSlug(String, String, Predicate)}), probed against the salon namespace (D2/D3).
     * Used directly by {@code SalonService#createSalon}, which writes it with the INSERT.
     *
     * <p><b>Collision-proof INSERT (376 audit).</b> Each candidate's advisory lock
     * ({@link SalonRepository#lockBookingSlugCandidates}) is taken BEFORE its existence probe and held to
     * the caller's commit, so no other salon-slug writer can pick the same value in between — the INSERT
     * cannot hit {@code idx_salons_booking_slug}. A candidate already stored is replaced by a fresh one (the
     * generator's bounded retry). Must therefore run inside the writer's transaction; outside one the lock
     * would be released at once — hence {@code MANDATORY} (376 audit cycle 2): a caller without a
     * transaction fails with {@code IllegalTransactionStateException} instead of silently racing.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String generateSalonSlug(String salonName) {
        return generateSlug(salonName, SALON_FALLBACK_BASE, this::isSalonSlugTakenUnderLock);
    }

    private boolean isSalonSlugTakenUnderLock(String candidate) {
        salonRepository.lockBookingSlugCandidates(new String[] {candidate});
        return salonRepository.existsByBookingSlug(candidate);
    }

    /**
     * Phase 376 (audit perf LOW) — race-safe lazy issue for ALL of a caller's legacy
     * ({@code booking_slug NULL}) salons on {@code GET /booking-links/mine}, in ONE short transaction and
     * a constant number of statements (row lock, candidate lock, probe, one batched {@code UPDATE}) instead
     * of one transaction per salon.
     *
     * <p>The rows still lacking a slug are locked in id order ({@link SalonRepository#lockSalonIdsWithoutBookingSlug});
     * a salon a concurrent call issued meanwhile drops out there and its committed slug is re-read, so
     * every caller observes the one permanent slug (D4/D5). Candidates are locked before they are probed
     * (see {@link #generateSalonSlug}), so the {@code UPDATE} cannot hit {@code idx_salons_booking_slug}
     * either; should it ever, the violation marks this transaction rollback-only and the caller's single
     * retry ({@code BookingLinkService#issueMissingSalonSlugs}) runs in a fresh one — so callers must NOT
     * wrap this in an outer transaction.
     *
     * <p>A salon deleted after the caller's read (376 audit cycle 2) is neither locked nor re-read, so it is
     * simply absent from the result — the caller omits its link rather than failing the whole request.
     *
     * @param salons the caller's active salons it read with a {@code null} slug (id + name, no re-read)
     * @return salon id → its permanent slug, for every given salon that still exists
     */
    @Transactional
    public Map<UUID, String> issueMissingSalonSlugs(List<SalonBookingLinkRow> salons) {
        List<UUID> salonIds = salons.stream().map(SalonBookingLinkRow::getId).toList();
        Set<UUID> stillMissing = new HashSet<>(salonRepository.lockSalonIdsWithoutBookingSlug(salonIds));
        Map<UUID, String> slugs = allocateSalonSlugs(salons.stream()
                .filter(salon -> stillMissing.contains(salon.getId()))
                .toList());
        if (!slugs.isEmpty()) {
            UUID[] issuedIds = slugs.keySet().toArray(UUID[]::new);
            salonRepository.assignBookingSlugsIfAbsent(
                    issuedIds, Arrays.stream(issuedIds).map(slugs::get).toArray(String[]::new));
        }
        putCommittedSalonSlugs(salonIds.stream().filter(salonId -> !slugs.containsKey(salonId)).toList(), slugs);
        return slugs;
    }

    /** Re-reads the concurrently issued slugs in ONE statement; a salon gone meanwhile stays absent. */
    private void putCommittedSalonSlugs(List<UUID> losers, Map<UUID, String> slugs) {
        if (losers.isEmpty()) {
            return;
        }
        salonRepository.findBookingSlugsByIds(losers.toArray(UUID[]::new))
                .forEach(row -> slugs.put(row.getId(), row.getBookingSlug()));
    }

    /**
     * Batched twin of {@link #generateSlug(String, String, Predicate)}: the same base and random suffix,
     * the same {@value #MAX_GENERATION_ATTEMPTS}-round bound, but each round locks (sorted, one statement)
     * and probes (one statement) all pending candidates at once. A candidate already stored, or drawn twice
     * within the batch, is redrawn next round.
     */
    private Map<UUID, String> allocateSalonSlugs(List<SalonBookingLinkRow> salons) {
        Map<UUID, String> bases = new LinkedHashMap<>();
        salons.forEach(salon -> bases.put(salon.getId(), buildBase(salon.getName(), SALON_FALLBACK_BASE)));
        Map<UUID, String> allocated = new HashMap<>();
        List<UUID> pending = List.copyOf(bases.keySet());
        for (int round = 0; round < MAX_GENERATION_ATTEMPTS && !pending.isEmpty(); round++) {
            pending = allocateRound(pending, bases, allocated);
        }
        if (!pending.isEmpty()) {
            throw slugExhausted();
        }
        return allocated;
    }

    /** One lock + probe round; returns the salons whose candidate was taken (to redraw). */
    private List<UUID> allocateRound(List<UUID> pending, Map<UUID, String> bases, Map<UUID, String> allocated) {
        Map<UUID, String> candidates = new LinkedHashMap<>();
        pending.forEach(salonId -> candidates.put(salonId, bases.get(salonId) + "-" + randomSuffix()));
        List<String> sorted = candidates.values().stream().distinct().sorted().toList();
        salonRepository.lockBookingSlugCandidates(sorted.toArray(String[]::new));
        Set<String> claimed = new HashSet<>(salonRepository.findExistingBookingSlugs(sorted));
        claimed.addAll(allocated.values());
        List<UUID> redraw = new ArrayList<>();
        candidates.forEach((salonId, candidate) -> {
            if (claimed.add(candidate)) {
                allocated.put(salonId, candidate);
            } else {
                redraw.add(salonId);
            }
        });
        return redraw;
    }

    /**
     * The one slug generator (phase 376 D3) shared by masters and salons: transliterates and normalises
     * {@code displayBase} (falling back to {@code fallbackBase} below 2 chars), then appends
     * {@code '-'} + 4 random {@code [a-z0-9]} chars, retrying up to {@value #MAX_GENERATION_ATTEMPTS}
     * times while {@code exists} reports a collision in the target namespace.
     */
    private String generateSlug(String displayBase, String fallbackBase, Predicate<String> exists) {
        String base = buildBase(displayBase, fallbackBase);
        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
            String candidate = base + "-" + randomSuffix();
            if (!exists.test(candidate)) {
                return candidate;
            }
        }
        throw slugExhausted();
    }

    private static BusinessException slugExhausted() {
        return new BusinessException(HttpStatus.CONFLICT, "Could not allocate a unique booking slug");
    }

    /**
     * Resolves a public slug to the unauthenticated booking-page payload (master
     * display name, avatar, bio, and active services). Throws
     * {@link NotFoundException} (→ 404) when the slug is unknown or the master is
     * inactive.
     */
    @Cacheable(cacheNames = "booking-slug-info", key = "#slug", sync = true)
    @Transactional(readOnly = true)
    public BookingSlugInfoResponse findBySlug(String slug) {
        Master master = masterRepository.findByBookingSlugWithUser(slug)
                .orElseThrow(() -> new NotFoundException("Booking page not found"));

        User user = master.getUser();
        List<ServiceSummaryDto> services = masterServiceRepository
                .findByMasterIdAndIsActiveTrueWithGraph(master.getId(), PageRequest.of(0, MAX_SERVICES))
                .stream()
                .map(ServiceSummaryDto::from)
                .toList();

        boolean discloses = MasterType.disclosesOwnAddress(master.getMasterType());
        return new BookingSlugInfoResponse(
                composeName(user.getFirstName(), user.getLastName()),
                user.getAvatarUrl(),
                user.getBio(),
                services,
                master.getId(),
                master.getMasterType(),
                user.getProfessionalTitle(),
                user.getCity(),
                user.getRegion(),
                salonRef(master.getSalon()),
                // Same rule as MasterDetailResponse: no reviews -> null, never the stored 0.00.
                master.getReviewCount() == 0 ? null : master.getAvgRating(),
                master.getReviewCount(),
                categories(master.getId()),
                portfolio(master.getId()),
                latestReviews(master.getId()),
                Boolean.TRUE.equals(masterRepository.isBookableById(master.getId(), scheduleDateMath.today())),
                latest(master.getUpdatedAt(), user.getUpdatedAt()),
                // Phase 383 D5: the method behind GET /masters/{id}/reviews/summary (zero-filled 5..1).
                reviewService.getMasterReviewSummary(master.getId()).ratingDistribution(),
                // Phase 383 D4: the app's public-profile address rule (MasterDetailResponse#fromPublic) — the same
                // MasterType#disclosesOwnAddress predicate; a salon master discloses none of the three.
                discloses ? user.getStreet() : null,
                discloses ? user.getBuildingNo() : null,
                discloses ? user.getLocationNote() : null);
    }

    /** Phase 383 D6 — page-size ceiling of the public {@code /reviews} routes (a larger request is clamped). */
    public static final int MAX_REVIEWS_PAGE_SIZE = 20;

    /**
     * Phase 383 D6 — one page of a master's public reviews, addressed by the booking slug. Slug resolution is
     * {@code /info}'s (unknown / inactive master or salon → 404); the page itself is the method behind
     * {@code GET /masters/{id}/reviews} (same sort enum, same {@link ReviewResponse}, same
     * {@code reviews-by-master} cache — so the existing review-created eviction already covers it), with the
     * requested size clamped to {@value #MAX_REVIEWS_PAGE_SIZE}. A master that is not {@code bookable} still
     * returns its reviews, as {@code /info} renders them.
     */
    @Transactional(readOnly = true)
    public Page<ReviewResponse> reviewsForSlug(String slug, SalonReviewSort sort, Pageable pageable) {
        UUID masterId = masterRepository.findActiveIdByBookingSlug(slug)
                .orElseThrow(() -> new NotFoundException("Booking page not found"));
        Pageable clamped = PageRequest.of(pageable.getPageNumber(),
                Math.min(pageable.getPageSize(), MAX_REVIEWS_PAGE_SIZE));
        return reviewService.getReviewsForMaster(masterId, sort, clamped);
    }

    /**
     * Phase 384 D4 — one page of a salon's public reviews, addressed by the salon booking slug: slug resolution is
     * the salon page's (unknown / inactive salon → 404), the page is the method behind
     * {@code GET /salons/{id}/reviews} ({@code SalonReviewResponse}, same sort enum, same {@code reviews-by-salon}
     * cache and eviction), with the size clamped like {@link #reviewsForSlug}.
     */
    @Transactional(readOnly = true)
    public Page<SalonReviewResponse> salonReviewsForSlug(String slug, SalonReviewSort sort, Pageable pageable) {
        UUID salonId = salonRepository.findActiveIdByBookingSlug(slug)
                .orElseThrow(() -> new NotFoundException("Booking page not found"));
        Pageable clamped = PageRequest.of(pageable.getPageNumber(),
                Math.min(pageable.getPageSize(), MAX_REVIEWS_PAGE_SIZE));
        return reviewService.getSalonReviews(salonId, sort, clamped);
    }

    private static BookingSlugSalonRef salonRef(Salon salon) {
        return salon == null ? null : new BookingSlugSalonRef(salon.getName(), salon.getBookingSlug());
    }

    private List<CategoryRef> categories(UUID masterId) {
        return masterServiceRepository.findActiveCategoriesByMasterId(masterId).stream()
                .map(row -> {
                    CatalogCategory category = (CatalogCategory) row[0];
                    return new CategoryRef(category.getId(), category.getNameUk(), (String) row[1]);
                })
                .toList();
    }

    private List<MediaFileResponse> portfolio(UUID masterId) {
        // Uncached on purpose: the shared `portfolio` cache entry ignores page size (see MediaService).
        return mediaService.getLatestPortfolio(EntityType.MASTER, masterId, PORTFOLIO_LIMIT);
    }

    private List<ReviewResponse> latestReviews(UUID masterId) {
        return reviewService.getReviewsForMaster(
                masterId, SalonReviewSort.NEWEST, PageRequest.of(0, LATEST_REVIEWS_LIMIT)).getContent();
    }

    /** {@code GREATEST(a, b)} of the two audit timestamps (phase 380 D2 {@code updatedAt}). */
    private static Instant latest(Instant first, Instant second) {
        if (first == null || second == null) {
            return first != null ? first : second;
        }
        return first.isAfter(second) ? first : second;
    }

    private String buildBase(String displayBase, String fallbackBase) {
        String raw = displayBase == null ? "" : displayBase.trim();
        String base = normalize(transliterate(raw));
        if (base.length() < 2) {
            base = fallbackBase;
        }
        return truncateBase(base);
    }

    /** "first last", trimmed, null-safe — also the booking-link display name (phase 375). */
    static String composeName(String firstName, String lastName) {
        String first = firstName == null ? "" : firstName.trim();
        String last = lastName == null ? "" : lastName.trim();
        return (first + " " + last).trim();
    }

    private String transliterate(String input) {
        StringBuilder sb = new StringBuilder(input.length() * 2);
        for (char c : input.toLowerCase(Locale.ROOT).toCharArray()) {
            String mapped = UK_TO_LATIN.get(c);
            sb.append(mapped != null ? mapped : c);
        }
        return sb.toString();
    }

    /** Lowercase, strip to {@code [a-z0-9-]}, collapse and trim hyphens. */
    private String normalize(String input) {
        String ascii = input.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("-{2,}", "-");
        int start = 0;
        int end = ascii.length();
        while (start < end && ascii.charAt(start) == '-') {
            start++;
        }
        while (end > start && ascii.charAt(end - 1) == '-') {
            end--;
        }
        return ascii.substring(start, end);
    }

    /** Caps the base so {@code base + "-XXXX"} stays within {@value #MAX_SLUG_LENGTH}. */
    private String truncateBase(String base) {
        if (base.length() <= MAX_BASE_LENGTH) {
            return base;
        }
        String cut = base.substring(0, MAX_BASE_LENGTH);
        while (cut.endsWith("-")) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut;
    }

    private String randomSuffix() {
        StringBuilder sb = new StringBuilder(RANDOM_SUFFIX_LENGTH);
        for (int i = 0; i < RANDOM_SUFFIX_LENGTH; i++) {
            sb.append(SLUG_ALPHABET[random.nextInt(SLUG_ALPHABET.length)]);
        }
        return sb.toString();
    }
}
