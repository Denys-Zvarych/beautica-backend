package com.beautica.common.security;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * A request-lifetime memo of the two boolean <em>facts</em> the band-edit authorization pair reads
 * twice per request (perf LOW, 2026-09-13 cycle-2 audit, B5):
 * <ul>
 *   <li>"does {@code salonId} have owner {@code actorId}?" —
 *       {@code SalonRepository#existsByIdAndOwnerId}; and</li>
 *   <li>"does the {@code masters} row {@code masterId} belong to {@code salonId}?" —
 *       {@code MasterRepository#existsByIdAndSalonId}.</li>
 * </ul>
 *
 * <h2>The duplication it removes</h2>
 * {@code PATCH /salons/{salonId}/masters/{masterId}/services/{serviceDefId}} authorizes twice by
 * design: the controller's {@code @PreAuthorize} runs
 * {@code AuthorizationService#canEditMasterServiceBand}, and the service re-proves the same grant
 * through {@code AuthorizationService#enforceCanEditMasterServiceBand} so a non-HTTP caller cannot
 * bypass it (anti-bug §D defense-in-depth). For a {@code SALON_OWNER} actor that meant FOUR
 * authorization statements per request, two of them byte-identical repeats of the other two. The
 * {@code SALON_ADMIN} arm was already deduplicated the same way, by
 * {@link ActorSalonAssignmentMemo}; this is the missing owner-side half of that fix.
 *
 * <h2>Facts, not decisions — why this does not weaken the defense-in-depth twin</h2>
 * <b>What is memoised is the repository answer, never the grant.</b>
 * {@code enforceCanEditMasterServiceBand} still evaluates its own predicate, in its own order, with
 * its own {@code ForbiddenException}: deleting the SpEL gate entirely would change nothing about
 * what it concludes. Had the <em>decision</em> been cached instead, the service-layer twin would be
 * trusting the very layer it exists to distrust — which is exactly the property this class is
 * shaped to preserve.
 *
 * <p>The two facts are also the right KIND of thing to memoise, by the same test
 * {@link ActorSalonAssignmentMemo} applies to {@code users.salon_id}: both are {@code boolean}
 * projections rather than managed entities (no detachment or lazy-initialisation hazard), and
 * neither can change within the request that reads them — a band PATCH writes exactly one
 * {@code master_services} row and touches neither {@code salons.owner_id} nor
 * {@code masters.salon_id}. <b>Any future call site that reads this memo AFTER a write that could
 * move a salon's owner or a master's salon must not use it</b>: it would observe the pre-mutation
 * value. That is why this is a narrow, explicitly-called helper rather than something buried inside
 * {@code hasManagementAccess}, which every write path in the application reaches.
 *
 * <h2>Deliberately dumb, and request-ATTRIBUTE backed</h2>
 * A tiny insertion-ordered map in the current request's attributes: no eviction (the request ending
 * IS the eviction), no TTL, no cross-request state — so, unlike a {@code @Cacheable}, there is no
 * staleness window for anti-bug §F to demand an eviction path for. Request attributes rather than a
 * {@code @RequestScope} bean for the same reason as {@link ActorSalonAssignmentMemo}: a
 * {@code @RequestScope} proxy throws {@code IllegalStateException: No thread-bound request found}
 * off a servlet thread, and {@code AuthorizationService} is also reached by direct service calls
 * (integration tests drive {@code ServiceCatalogService} with a primed {@code SecurityContext} and
 * no bound request). A pure optimisation must never turn a working call into a 500, so with no
 * request bound this degrades to a plain uncached read.
 *
 * <p><b>Not a security boundary.</b> Every key carries the ids the fact is about, so an answer
 * memoised for one (salon, actor) or (master, salon) pair can never be handed to another.
 */
@Component
public class SalonScopeFactMemo {

    private static final String ATTRIBUTE = SalonScopeFactMemo.class.getName();

    /** Bounds the per-request map so a pathological caller cannot grow it without limit. */
    private static final int MAX_ENTRIES = 16;

    /**
     * Returns whether {@code actorId} owns {@code salonId}, reading it at most once per request.
     *
     * @param loader how to read it when this request has not — invoked at most once per request per
     *               pair, or on every call when no request is bound to the thread
     */
    public boolean ownsSalon(UUID salonId, UUID actorId, BooleanSupplier loader) {
        return recall("owner:" + salonId + ':' + actorId, loader);
    }

    /**
     * Returns whether the {@code masters} row {@code masterId} belongs to {@code salonId}, reading
     * it at most once per request.
     *
     * @param loader how to read it when this request has not — invoked at most once per request per
     *               pair, or on every call when no request is bound to the thread
     */
    public boolean masterInSalon(UUID masterId, UUID salonId, BooleanSupplier loader) {
        return recall("member:" + masterId + ':' + salonId, loader);
    }

    private boolean recall(String key, BooleanSupplier loader) {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request == null) {
            return loader.getAsBoolean();
        }
        Map<String, Boolean> facts = factsOf(request);
        Boolean cached = facts.get(key);
        if (cached != null) {
            return cached;
        }
        boolean loaded = loader.getAsBoolean();
        if (facts.size() < MAX_ENTRIES) {
            facts.put(key, loaded);
        }
        return loaded;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Boolean> factsOf(RequestAttributes request) {
        Object existing = request.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (existing instanceof Map<?, ?> map) {
            return (Map<String, Boolean>) map;
        }
        Map<String, Boolean> facts = new LinkedHashMap<>();
        request.setAttribute(ATTRIBUTE, facts, RequestAttributes.SCOPE_REQUEST);
        return facts;
    }
}
