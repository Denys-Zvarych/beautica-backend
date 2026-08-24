package com.beautica.favorite.service;

import com.beautica.booking.service.LastBookedCategoryLookup;
import com.beautica.service.service.PlatformCategoryLabel;
import com.beautica.service.service.PlatformCategoryLabelResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Resolves the <b>category axis</b> of a favourites page — the single field the approved
 * design's category chips filter on, for both the masters list and the salons list.
 *
 * <h3>What the axis is</h3>
 * Per the approved design ({@code docs/signup-designs/ClientFavorites}): the category is
 * <em>derived server-side from the last booked service, so both kinds filter through one
 * axis</em>. The card never renders it — the FILTER is by category, the card never was — and
 * the client's per-category counts only decide which chips exist. That is why this is a
 * singular field and why it lives on both DTOs.
 *
 * <h3>Two collaborators, ONE extra round trip per page</h3>
 * <ol>
 *   <li>{@link LastBookedCategoryLookup} — one batched statement per page, keyed on the ids
 *       the caller already holds, resolving provider → {@code platform_categories.name}. This
 *       is the only added database work on the favourites read path.</li>
 *   <li>{@link PlatformCategoryLabelResolver} — turns that code into its Ukrainian
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
 * <h3>Both fields or neither</h3>
 * A code whose label cannot be resolved yields {@code null} for BOTH fields rather than a code
 * with a {@code null} label. The pair is the filter's identity: the client builds its chip set
 * from approved, selectable categories, so a code outside that vocabulary can match no chip,
 * and a chip with no label cannot be drawn. This also inherits
 * {@link PlatformCategoryLabelResolver}'s selectability gate for free — a PENDING or
 * deactivated category is invisible here exactly as it is invisible to search, so the two
 * surfaces cannot disagree about which categories exist.
 *
 * <h3>Nulls are expected, not a defect</h3>
 * A client who favourites a provider before ever booking with them gets {@code null} on both
 * fields, and favouriting normally PRECEDES booking, so the null rate is high by construction.
 * That is the design's accepted behaviour: the client hides categories with no rows, and
 * {@code null} means "no booked history with this provider yet". There is deliberately no
 * profile-derived or offering-derived fallback to manufacture a category — a fallback would
 * file a provider under a category the client has never actually booked, which is a different
 * (and wrong) answer to the question the chip asks.
 */
@Component
@RequiredArgsConstructor
public class FavoriteCategoryResolver {

    private final LastBookedCategoryLookup lastBookedCategoryLookup;
    private final PlatformCategoryLabelResolver platformCategoryLabelResolver;

    /**
     * Resolves the category axis for a page of favourited MASTER ids.
     *
     * @param clientId  the authenticated principal — this is their own booking history (§E-4)
     * @param masterIds the page's master ids
     * @return a lookup carrier; providers with no resolvable category are simply absent
     */
    public FavoriteCategories resolveForMasters(UUID clientId, Collection<UUID> masterIds) {
        return pair(lastBookedCategoryLookup.lastBookedCategoryByMaster(clientId, masterIds));
    }

    /**
     * Resolves the category axis for a page of favourited SALON ids.
     *
     * @param clientId the authenticated principal
     * @param salonIds the page's salon ids
     * @return a lookup carrier; providers with no resolvable category are simply absent
     */
    public FavoriteCategories resolveForSalons(UUID clientId, Collection<UUID> salonIds) {
        return pair(lastBookedCategoryLookup.lastBookedCategoryBySalon(clientId, salonIds));
    }

    /**
     * Joins each provider's category code to its display label in memory, dropping any code
     * that is not currently selectable (see the both-or-neither rule in the class javadoc).
     */
    private FavoriteCategories pair(Map<UUID, String> codeByProvider) {
        if (codeByProvider.isEmpty()) {
            return FavoriteCategories.empty();
        }
        Map<String, String> labelByCode = new HashMap<>();
        for (PlatformCategoryLabel label : platformCategoryLabelResolver.selectableLabels()) {
            labelByCode.put(label.name(), label.displayName());
        }

        Map<UUID, FavoriteCategory> byProvider = new HashMap<>(codeByProvider.size());
        codeByProvider.forEach((providerId, code) -> {
            String label = labelByCode.get(code);
            if (label != null) {
                byProvider.put(providerId, new FavoriteCategory(code, label));
            }
        });
        return new FavoriteCategories(byProvider);
    }

    /**
     * One provider's resolved category axis: the travelling {@code platform_categories.name}
     * code and its Ukrainian {@code display_name}.
     *
     * <p>The code — not the {@code BIGSERIAL} {@code platform_categories.id} — is the identity
     * that travels, because it is the value denormalised into
     * {@code service_definitions.category} with no FK (V64). It is also the shape the existing
     * {@code ApprovedCategoryResponse(name, displayName)} contract already publishes to the
     * same client, so the mobile side matches one vocabulary, not two.
     */
    public record FavoriteCategory(String code, String label) {}

    /**
     * Page-scoped provider → {@link FavoriteCategory} lookup, mirroring
     * {@code DiscoveryLocationResolver.DiscoveryLabels}: built once per page, read per row,
     * and returning {@code null} for an absent provider so the mapper can stamp the DTO
     * without a null check of its own.
     */
    public record FavoriteCategories(Map<UUID, FavoriteCategory> byProvider) {

        static FavoriteCategories empty() {
            return new FavoriteCategories(Map.of());
        }

        /** The category code for {@code providerId}, or {@code null} when it has none. */
        public String code(UUID providerId) {
            FavoriteCategory category = byProvider.get(providerId);
            return category == null ? null : category.code();
        }

        /** The display label for {@code providerId}, or {@code null} when it has none. */
        public String label(UUID providerId) {
            FavoriteCategory category = byProvider.get(providerId);
            return category == null ? null : category.label();
        }
    }
}
