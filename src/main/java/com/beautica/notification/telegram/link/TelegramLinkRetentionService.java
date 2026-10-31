package com.beautica.notification.telegram.link;

import com.beautica.common.security.PhoneHasher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.IntSupplier;

/**
 * Phase 409 — how long Telegram link data lives (privacy policy 1.4, website 021):
 * <ul>
 *   <li>D1 {@link #unlinkPhonesWithoutOpenBookings} — a phone's link goes with its guest PII (retention batch);
 *       {@link #deleteOrphanLinks} serves the weekly {@link TelegramLinkOrphanBackstop} for links no batch ever reached;</li>
 *   <li>D2 {@link #purgeBlockedLinks} — a {@code BLOCKED} link {@value #BLOCKED_RETENTION_MONTHS} months after
 *       {@code status_changed_at} (fixed, independent of {@code retention-months});</li>
 *   <li>D3 {@link #eraseForPhone} — on a guest-data erase, the link and every token of the phone;</li>
 *   <li>D4 {@link #purgeExpiredTokens} — tokens of any state one day past {@code expires_at}
 *       ({@link TelegramLinkTokenPurgeJob}).</li>
 * </ul>
 * Opt-out ({@code /stop}, «Повернутися до SMS») is the immediate {@link TelegramLinkService#unlinkChat} /
 * {@link TelegramLinkService#unlinkPhone} delete (405/407). Callers log counts only — no phone, hash, chat id or token
 * ever leaves this class.
 */
@Service
@RequiredArgsConstructor
public class TelegramLinkRetentionService {

    /** D2 — privacy policy 1.4: a BLOCKED link is kept 12 months after the block. Not configurable on purpose. */
    public static final int BLOCKED_RETENTION_MONTHS = 12;
    /** Backstop page size: links per keyset read / delete statement. */
    static final int ORPHAN_PAGE_SIZE = 1_000;

    private final TelegramLinkRepository linkRepository;
    private final TelegramLinkTokenRepository tokenRepository;
    private final PhoneHasher phoneHasher;

    /** Counts from one erase; never carries a phone or a hash. */
    public record Erasure(int links, int tokens) {
    }

    /**
     * D1 — inside the caller's (retention batch) transaction: of {@code phonesE164}, takes the per-phone retention
     * lock on every phone that has a link (hash order), then asks {@code phonesWithOpenBookings} which still have a
     * live booking, and deletes the links of the rest. The lock-then-check order closes the two-instance race on a
     * phone's last two bookings (see {@link TelegramLinkRepository#acquirePhoneRetentionLock}).
     *
     * @param phonesWithOpenBookings returns the subset of its argument that still has a non-anonymised booking
     * @return links deleted
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int unlinkPhonesWithoutOpenBookings(Collection<String> phonesE164,
                                               Function<Collection<String>, Collection<String>> phonesWithOpenBookings) {
        Map<String, String> phoneByHashHex = hashByPhone(phonesE164);
        if (phoneByHashHex.isEmpty()) {
            return 0;
        }
        TreeMap<String, String> linked = new TreeMap<>();
        for (byte[] hash : linkRepository.findLinkedPhoneHashes(toHashes(phoneByHashHex.keySet()))) {
            String hex = HexFormat.of().formatHex(hash);
            linked.put(hex, phoneByHashHex.get(hex));
        }
        if (linked.isEmpty()) {
            return 0;
        }
        linked.keySet().forEach(hex -> linkRepository.acquirePhoneRetentionLock(HexFormat.of().parseHex(hex)));
        Set<String> stillBooked = new HashSet<>(phonesWithOpenBookings.apply(List.copyOf(linked.values())));
        linked.values().removeIf(stillBooked::contains);
        return linked.isEmpty() ? 0 : linkRepository.deleteByPhones(toHashes(linked.keySet()));
    }

    /**
     * D3 — inside the erase transaction: the phone's issue lock (so no token is issued mid-erase), its tokens, then
     * its link. Tokens before the link: the order a proof locks in (token row, then the link upsert).
     *
     * @param phoneE164 the erase's already-normalised phone
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Erasure eraseForPhone(String phoneE164) {
        byte[] hash = phoneHasher.hash(phoneE164).hash();
        tokenRepository.acquirePhoneIssueLock(hash);
        int tokens = tokenRepository.deleteAllByPhoneHash(hash);
        int links = linkRepository.deleteByPhone(hash);
        return new Erasure(links, tokens);
    }

    /**
     * D2 — deletes {@code BLOCKED} links whose {@code status_changed_at} is more than
     * {@value #BLOCKED_RETENTION_MONTHS} calendar months (UTC) before {@code now}, in batches, each its own
     * transaction, until a short batch or {@code maxBatches}.
     *
     * @return links deleted
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int purgeBlockedLinks(Instant now, int batchSize, int maxBatches) {
        Instant cutoff = blockedCutoff(now);
        return inBatches(() -> linkRepository.deleteBlockedBatchBefore(cutoff, batchSize), batchSize, maxBatches);
    }

    static Instant blockedCutoff(Instant now) {
        return now.atOffset(ZoneOffset.UTC).minusMonths(BLOCKED_RETENTION_MONTHS).toInstant();
    }

    /**
     * D4 — deletes tokens (any state) whose {@code expires_at} is before {@code cutoff}, batched like
     * {@link #purgeBlockedLinks}.
     *
     * @return tokens deleted
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int purgeExpiredTokens(Instant cutoff, int batchSize, int maxBatches) {
        return inBatches(() -> tokenRepository.deleteExpiredBatchBefore(cutoff, batchSize), batchSize, maxBatches);
    }

    /**
     * D1 backstop — deletes links created before {@code linkedBefore} whose phone hash is not in
     * {@code openPhones} (every phone that still has a non-anonymised guest booking). Keyset pages
     * of {@value #ORPHAN_PAGE_SIZE}, each delete its own transaction; at most {@code maxPages} pages.
     *
     * @return links deleted
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int deleteOrphanLinks(PhoneHashPrefixes openPhones, Instant linkedBefore, int maxPages) {
        UUID after = new UUID(0L, 0L); // Postgres orders uuid as unsigned bytes: all-zero is the minimum
        int deleted = 0;
        for (int page = 0; page < maxPages; page++) {
            List<TelegramLinkRepository.LinkKey> keys =
                    linkRepository.findLinkKeysPage(after, linkedBefore, ORPHAN_PAGE_SIZE);
            List<UUID> orphans = new ArrayList<>();
            for (TelegramLinkRepository.LinkKey key : keys) {
                if (!openPhones.contains(key.getPhoneHash())) {
                    orphans.add(key.getId());
                }
            }
            if (!orphans.isEmpty()) {
                deleted += linkRepository.deleteOrphansByIds(orphans, linkedBefore);
            }
            if (keys.size() < ORPHAN_PAGE_SIZE) {
                break;
            }
            after = keys.get(keys.size() - 1).getId();
        }
        return deleted;
    }

    private Map<String, String> hashByPhone(Collection<String> phonesE164) {
        Map<String, String> phoneByHashHex = new LinkedHashMap<>();
        for (String phone : phonesE164) {
            phoneHasher.tryHash(phone)
                    .ifPresent(hashed -> phoneByHashHex.put(HexFormat.of().formatHex(hashed.hash()), phone));
        }
        return phoneByHashHex;
    }

    private static List<byte[]> toHashes(Collection<String> hexes) {
        return hexes.stream().map(HexFormat.of()::parseHex).toList();
    }

    private static int inBatches(IntSupplier batch, int batchSize, int maxBatches) {
        int total = 0;
        for (int i = 0; i < maxBatches; i++) {
            int deleted = batch.getAsInt();
            total += deleted;
            if (deleted < batchSize) {
                break;
            }
        }
        return total;
    }
}
