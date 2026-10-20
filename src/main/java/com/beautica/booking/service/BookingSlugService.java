package com.beautica.booking.service;

import com.beautica.booking.dto.BookingSlugInfoResponse;
import com.beautica.booking.dto.ServiceSummaryDto;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.master.entity.Master;
import com.beautica.master.repository.MasterRepository;
import com.beautica.service.repository.MasterServiceRepository;
import com.beautica.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

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
    private static final char[] SLUG_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    /** Public master menu is tiny; cap the unbounded list read defensively (Anti-Bug §E-3). */
    private static final int MAX_SERVICES = 100;

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
        String base = buildBase(firstName, lastName);
        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
            String candidate = base + "-" + randomSuffix();
            if (!masterRepository.existsByBookingSlug(candidate)) {
                return candidate;
            }
        }
        throw new BusinessException(
                HttpStatus.CONFLICT, "Could not allocate a unique booking slug");
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

        return new BookingSlugInfoResponse(
                composeName(user.getFirstName(), user.getLastName()),
                user.getAvatarUrl(),
                user.getBio(),
                services);
    }

    private String buildBase(String firstName, String lastName) {
        String raw = composeName(firstName, lastName);
        String base = normalize(transliterate(raw));
        if (base.length() < 2) {
            base = FALLBACK_BASE;
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
