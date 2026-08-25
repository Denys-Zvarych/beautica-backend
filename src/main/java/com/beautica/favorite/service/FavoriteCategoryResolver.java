package com.beautica.favorite.service;

import com.beautica.favorite.dto.FavoriteCategoryView;
import com.beautica.service.service.OfferedCategoryLookup;
import com.beautica.service.service.PlatformCategoryLabel;
import com.beautica.service.service.PlatformCategoryLabelResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Resolves the <b>category axis</b> of a favourites page — the filter the approved design's
 * category chips select on, for both the masters list and the salons list.
 *
 * <h3>What the axis is (reversed from the original design)</h3>
 * The category axis was originally the single platform category of the client's most recently
 * <em>booked</em> service with each provider. That was a deliberate design decision, and this
 * class's javadoc used to argue for it at length. <b>The product decision has since been
 * reversed.</b> The axis is now every distinct platform category a provider actually
 * <em>offers</em> — every ACTIVE service category a master performs, or, for a salon, every
 * ACTIVE service category at least one of its currently active masters performs (the LOCKED
 * "salon offering = master-performed only" domain rule). A provider who works in several
 * categories now surfaces under every one of them, so several chips can each match the same card.
 *
 * <p>The reason for the reversal: booking history answers "what has THIS CLIENT already bought
 * here", which is a poor proxy for "what can I filter my saved providers by". Favouriting
 * normally precedes booking, so the old axis was {@code null} on both fields for the common case
 * — a client who saves a provider before ever booking them could not filter that provider by
 * category at all, on the one screen whose entire purpose is filtering saved providers. The new
 * axis answers the question the chip actually asks: "what does this provider do", independent of
 * whether this client has bought it yet. It is also no longer client-scoped at all — offering is
 * a fact about the provider, not about the asking client — which is why {@link #resolveForMasters}
 * and {@link #resolveForSalons} below no longer take a {@code clientId}.
 *
 * <h3>Two collaborators, ONE extra round trip per page</h3>
 * <ol>
 *   <li>{@link OfferedCategoryLookup} — one batched statement per page, keyed on the ids the
 *       caller already holds, resolving provider → distinct {@code platform_categories.name}
 *       codes it offers. This is the only added database work on the favourites read path,
 *       exactly as the booking-history lookup it replaced was.</li>
 *   <li>{@link PlatformCategoryLabelResolver} — turns each code into its Ukrainian
 *       {@code display_name}. It costs <b>no round trip at all</b>: it is backed by the
 *       already-{@code @Cacheable} approved+active category list
 *       ({@code platform-category-order}, 60-min TTL, {@code sync = true}), which is
 *       admin-gated reference data on the order of a couple of dozen rows and is evicted by
 *       the only two writes that can change it. Joining {@code platform_categories} inside the
 *       batch statement instead would have paid a join per page for data already in memory.</li>
 * </ol>
 * So the favourites list goes from N statements to N+1, never to N+2, and the list projection
 * query itself is untouched — the 0.089 ms it reached when the old per-row {@code LATERAL} was
 * deleted is preserved by keeping this derivation in a separate, page-bounded statement.
 *
 * <h3>Both entries or neither, per category</h3>
 * A code whose label cannot be resolved contributes NO entry to the list, rather than an entry
 * with a {@code null} label. The pair is the filter's identity: the client builds its chip set
 * from approved, selectable categories, so a code outside that vocabulary can match no chip, and
 * a chip with no label cannot be drawn. This also inherits {@link PlatformCategoryLabelResolver}'s
 * selectability gate for free — a PENDING or deactivated category is invisible here exactly as it
 * is invisible to search, so the two surfaces cannot disagree about which categories exist.
 *
 * <h3>Empty is expected, not a defect</h3>
 * A provider with no active categorisable service (a brand-new profile, or one whose only
 * services are all inactive) gets an empty list, never {@code null} — the client iterates
 * directly with no null-check of its own.
 */
@Component
@RequiredArgsConstructor
public class FavoriteCategoryResolver {

    private final OfferedCategoryLookup offeredCategoryLookup;
    private final PlatformCategoryLabelResolver platformCategoryLabelResolver;

    /**
     * Resolves the category axis for a page of favourited MASTER ids.
     *
     * @param masterIds the page's master ids
     * @return a lookup carrier; a provider offering nothing categorisable simply carries an
     *         empty list, never {@code null}
     */
    public FavoriteCategories resolveForMasters(Collection<UUID> masterIds) {
        return pair(offeredCategoryLookup.offeredCategoriesByMaster(masterIds));
    }

    /**
     * Resolves the category axis for a page of favourited SALON ids.
     *
     * @param salonIds the page's salon ids
     * @return a lookup carrier; a salon offering nothing categorisable simply carries an empty
     *         list, never {@code null}
     */
    public FavoriteCategories resolveForSalons(Collection<UUID> salonIds) {
        return pair(offeredCategoryLookup.offeredCategoriesBySalon(salonIds));
    }

    /**
     * Joins each provider's offered category codes to their display labels in memory, dropping
     * any code that is not currently selectable (see the both-or-neither rule in the class
     * javadoc), and orders the survivors by the same display-label order
     * {@link PlatformCategoryLabelResolver#selectableLabels()} returns — a stable order keeps a
     * provider's chip list identical across requests instead of following arbitrary row order.
     */
    private FavoriteCategories pair(Map<UUID, List<String>> codesByProvider) {
        if (codesByProvider.isEmpty()) {
            return FavoriteCategories.empty();
        }
        List<PlatformCategoryLabel> selectable = platformCategoryLabelResolver.selectableLabels();

        Map<UUID, List<FavoriteCategoryView>> byProvider = new HashMap<>(codesByProvider.size());
        codesByProvider.forEach((providerId, codes) -> {
            Set<String> offered = new HashSet<>(codes);
            List<FavoriteCategoryView> views = new ArrayList<>();
            for (PlatformCategoryLabel label : selectable) {
                if (offered.contains(label.name())) {
                    views.add(new FavoriteCategoryView(label.name(), label.displayName()));
                }
            }
            if (!views.isEmpty()) {
                byProvider.put(providerId, List.copyOf(views));
            }
        });
        return new FavoriteCategories(byProvider);
    }

    /**
     * Page-scoped provider → categories lookup, mirroring
     * {@code DiscoveryLocationResolver.DiscoveryLabels}: built once per page, read per row, and
     * returning an empty list for an absent provider so the mapper can stamp the DTO without a
     * null check of its own.
     */
    public record FavoriteCategories(Map<UUID, List<FavoriteCategoryView>> byProvider) {

        static FavoriteCategories empty() {
            return new FavoriteCategories(Map.of());
        }

        /** The category chips for {@code providerId}, or an empty list when it offers none. */
        public List<FavoriteCategoryView> categories(UUID providerId) {
            return byProvider.getOrDefault(providerId, List.of());
        }
    }
}
