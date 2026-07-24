package com.beautica.booking.dto;

import com.beautica.auth.Role;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.common.TimeZones;
import com.beautica.master.entity.Master;
import com.beautica.salon.entity.Salon;
import com.beautica.user.User;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * PII access contract: the controller MUST verify the caller is the booking's client
 * or the assigned master/owner before invoking {@code from(booking, ...)}.
 *
 * <p>{@code clientFirstName}/{@code clientLastName}/{@code clientAvatarUrl} are intentionally
 * visible to SALON_MASTER actors — the master needs the client's name and photo on their calendar.
 * No field-level role differentiation is applied. If {@code canViewBooking} scope ever widens,
 * audit this DTO.
 *
 * <p><b>{@code clientAvatarUrl} — deliberate widening of the client-PII surface.</b> This DTO
 * previously exposed the client's NAME to the provider; it now also exposes their LIKENESS (photo).
 * That was a considered decision, not an incidental addition, so record the reasoning here rather
 * than re-litigating it at each audit:
 * <ul>
 *   <li><b>Need.</b> The provider-side "Мої записи" timeline renders one card per booking; without
 *       a URL it can only draw a generic person glyph. Recognising the client walking in is the
 *       whole point of the card.</li>
 *   <li><b>Scope.</b> Every path that builds this DTO is row-scoped to the caller BEFORE any
 *       mapping happens, so no actor can enumerate a stranger's photo:
 *       {@code GET /bookings/me} resolves the scope from the JWT principal alone — never a request
 *       parameter — via {@code findIdsByMasterIdFiltered(master.getId(), …)} (master id looked up
 *       from the authenticated user) or {@code findIdsBySalonIdsFiltered(salonIds, …)} (salon ids
 *       from {@code findIdsByOwnerIdAndIsActiveTrue(actorUserId)}), with SALON_ADMIN rejected
 *       outright; {@code GET /bookings/{id}} passes every row through
 *       {@code AuthorizationService#enforceCanViewBooking}. A provider therefore only ever sees the
 *       avatars of clients who booked with them.</li>
 *   <li><b>Sensitivity.</b> The value is the same already-public Cloudflare R2 object URL that
 *       {@code MasterDetailResponse}, {@code SalonResponse}, {@code MasterSearchResult} and this
 *       DTO's own {@code masterAvatarUrl} hand out — an unauthenticated-readable bucket URL built
 *       once at upload by {@code R2StorageService#buildPublicUrl} and stored verbatim in
 *       {@code users.avatar_url}. It is NOT a signed URL and NOT a raw storage key, so passing it
 *       through grants no capability the URL holder did not already have.</li>
 *   <li><b>Why the CLIENT projection path populates it too — do NOT "optimise" this away.</b>
 *       Only the provider timeline consumes this field, and the two {@code GET /bookings/me}
 *       variants are served by different queries: a provider is hydrated by
 *       {@code findAllByIdsWithGraph} (entity path, {@code from(booking, …)}), a client by
 *       {@code hydrateClientBookingDetails} (JPQL projection, {@code b.client.avatarUrl}). So
 *       dropping {@code b.client.avatarUrl} from that projection would NOT break the feature, and
 *       it looks like free savings — on the client path the value is just the caller's own photo,
 *       which their app already holds from {@code /users/me}. It was measured (backend-perf, Phase
 *       26.x audit) at ~125–150 chars x 20 rows ≈ 3 KB per page. It is populated anyway, and the
 *       reason is correctness, not cost:
 *       <b>the same client fetching the same booking would otherwise get two different values for
 *       this field depending on which endpoint served it</b> — {@code null} from
 *       {@code GET /bookings/me}, their real photo from {@code GET /bookings/{id}}, which shares
 *       this DTO and always reads {@code client.getAvatarUrl()}. That also breaks the
 *       "{@code null} ⇒ render the fallback glyph" contract the {@code @Schema} below states,
 *       because the null would no longer mean "there is no photo" but "we withheld it from you" —
 *       a field whose emptiness encodes the CALLER'S ROLE rather than the data. Any consumer that
 *       caches by booking id, or diffs the list row against the detail view, sees a phantom
 *       change. Note also that the redundancy is the maximally compressible kind: on the client
 *       path it is the SAME string on every row, so {@code server.compression} (now enabled
 *       app-wide in {@code application.yml}: gzip, {@code min-response-size: 1KB}) collapses it to
 *       near nothing — the real fix was there, not here. {@code BookingDetailContractIT}'s reflective entity-vs-projection parity
 *       loop pins this: it enumerates {@code getRecordComponents()}, so stopping population here
 *       fails that gate BY DESIGN. That failure is a true positive, not collateral — suppressing
 *       it with a per-field carve-out would blunt the gate that exists because a COALESCE
 *       divergence between these very two mappers once leaked the master's personal
 *       {@code locationNote} (HIGH regression).</li>
 * </ul>
 * Symmetric with {@code masterAvatarUrl}, which has always exposed the provider's likeness to the
 * client on this same DTO.
 *
 * <p><b>Guest (LINK) bookings.</b> A guest booking has no registered account —
 * {@code client_id} is {@code NULL} (V89 {@code chk_bookings_guest_fields}) — so {@code clientId}
 * is nullable here. {@code clientFirstName}/{@code clientLastName} fall back to the booking's
 * OTP-verified {@code guestName}/{@code guestSurname} so the owning provider (the only actor who
 * can ever reach a guest booking's detail view — {@code enforceCanViewBooking} never admits a
 * CLIENT actor onto a null-client booking) still has a name to put on their calendar.
 * {@code guestPhone} is deliberately NOT surfaced by this DTO — it is SMS-transport PII, not
 * calendar-display PII, and has no field here to leak into. {@code clientAvatarUrl} has NO guest
 * fallback and is always {@code null} for a guest booking: there is no account, hence no uploaded
 * photo, and nothing to fall back TO — unlike the name, which falls back to the OTP-verified
 * {@code guestName}/{@code guestSurname}. Guest cards render the generic glyph.
 *
 * <p><b>Phase 19.3 — client enrichment.</b> Adds the master's avatar/type, the (nullable)
 * salon name, the master's discovery address (district-primary locality labels + street/
 * building), the service category, and {@code canReview}. {@code masterProfessionalTitle}
 * (added later, alongside the other {@code master*} fields) is likewise nullable — a master
 * may never have set one; render nothing (not a placeholder string) when null. {@code canReview}
 * and the resolved
 * {@code cityLabel}/{@code districtLabel} are NOT derivable from the entity graph alone —
 * {@code canReview} is the COMPLETED+no-review predicate computed by the service, and the
 * locality labels come from the {@code DiscoveryLocationResolver} M2 seam (same FK-join
 * label resolution {@code MasterSearchResult} uses). The discovery locality is district-
 * primary via the salon link when the master is salon-employed, else the master's own user
 * row — mirroring {@code SearchService}'s {@code COALESCE(salon, user)} rule.
 *
 * <p><b>{@code locationNote} (client mobile phase 14.3 enrichment)</b> is the provider's
 * free-text arrival hint ("3-й поверх, код 1234"). It follows the EXACT SAME salon-vs-
 * independent resolution rule as {@code street}/{@code buildingNo} above — never a second,
 * parallel rule: a salon-employed master surfaces the salon's own {@code locationNote}, an
 * independent master surfaces their own user row's {@code locationNote}. Nullable — most
 * providers never write one.
 *
 * <p><b>Track 25.x — note visibility is MUTUAL, by locked product decision.</b>
 * {@code providerComment} (written by the provider on {@code /decline} or {@code /not-complete})
 * is returned to BOTH the provider AND the client on this same DTO — including on a
 * {@code NOT_COMPLETED} (no-show) booking. This is INTENTIONAL, not a PII/privacy leak: the
 * product decision is "all notes should be visible for all sides" (see
 * {@code docs/backend-phases}, track 25.x). Symmetrically, {@code clientCancellationNote}
 * (written by the client on {@code /cancel}) is returned to both the client AND the provider.
 * Do NOT add audience-based suppression of either field — a prior architect review flagged this
 * as a leak and that recommendation was explicitly rejected by the product owner. See
 * {@code BookingNoteVisibilityIT} for the tests that pin this behaviour.
 *
 * <p><b>{@code priceMaxAtBooking}</b> — a frozen snapshot column on the booking row (V119), the
 * companion to {@code priceAtBooking}'s floor; see {@link BookingPriceRange} for the rule that
 * computed it at creation. In short: non-null only when the master left this service's price as a
 * genuine RANGE (no {@code priceOverride}) at the moment of booking; otherwise null, meaning
 * "single price, render {@code priceAtBooking} alone". Never re-derived on read — a later service
 * edit must not rewrite a band the client already agreed to.
 */
public record BookingDetailResponse(
        UUID id,
        UUID clientId,
        UUID masterId,
        UUID masterServiceId,
        String serviceName,
        BookingStatus status,
        ZonedDateTime startsAt,
        ZonedDateTime endsAt,
        BigDecimal priceAtBooking,
        @Schema(types = {"number", "null"}, nullable = true,
                description = "The range ceiling agreed AT BOOKING TIME, present ONLY when the "
                        + "master left this service's price as a genuine RANGE (no priceOverride) "
                        + "when the booking was made. Null means a single price — render "
                        + "priceAtBooking alone. The client must never re-derive this from "
                        + "priceType/priceOverride; the decision is made server-side, once.")
        BigDecimal priceMaxAtBooking,
        int durationMinutesAtBooking,
        OffsetDateTime createdAt,
        String clientFirstName,
        String clientLastName,
        String masterFirstName,
        String masterLastName,
        @Schema(types = {"string", "null"}, nullable = true,
                description = "The master's professional title/headline (e.g. "
                        + "\"Перукар-стиліст\"), same field as MasterSummaryResponse/"
                        + "MasterDetailResponse. Nullable — a master may never have set one.")
        String masterProfessionalTitle,
        @Schema(types = {"string", "null"}, nullable = true,
                description = "The client's booking-creation note (written once at POST "
                        + "/bookings). Visible to the provider. Distinct from "
                        + "clientCancellationNote below — this is NOT the cancellation reason.")
        String clientComment,
        @Schema(types = {"string", "null"}, nullable = true,
                description = "Written by the provider on /decline or /not-complete. Visible to "
                        + "the CLIENT on both DECLINED and NOT_COMPLETED bookings — intentional, "
                        + "by locked product decision (\"all notes visible for all sides\"), NOT "
                        + "a privacy leak. Do not suppress this for any audience.")
        String providerComment,
        @Schema(types = {"string", "null"}, nullable = true,
                description = "Written by the CLIENT on /cancel. Visible to the provider — "
                        + "the symmetric counterpart of providerComment. Only ever non-null on a "
                        + "CANCELLED booking.")
        String clientCancellationNote,
        // ── Phase 19.3 client-enrichment fields ──────────────────────────────
        String masterAvatarUrl,
        Role masterType,
        String salonName,
        String cityLabel,
        String districtLabel,
        String street,
        String buildingNo,
        @Schema(types = {"string", "null"}, nullable = true,
                description = "The provider's free-text arrival hint (e.g. \"3-й поверх, код "
                        + "1234\", \"вхід з двору, дзвонити двічі\"). Resolved by the identical "
                        + "salon-vs-independent rule as street/buildingNo: a salon booking "
                        + "surfaces the salon's own note, an independent master surfaces their "
                        + "own note. Nullable — most providers never set one.")
        String locationNote,
        String categoryName,
        boolean canReview,
        @Schema(types = {"string", "null"}, format = "uuid", nullable = true,
                description = "The multi-service visit (BE-5) this booking belongs to, or null for a "
                        + "legacy single-service booking (appointment_id IS NULL). Strictly additive; "
                        + "when non-null the client can fetch the full visit via "
                        + "GET /appointments/{appointmentId}. Both mapper paths (entity + CLIENT "
                        + "projection) read the SAME appointment_id column, so they never diverge.")
        UUID appointmentId,
        // Appended LAST, after the UUID appointmentId, for the same reason priceMaxAtBooking sits
        // apart from priceAtBooking (see ClientBookingDetailProjection's javadoc): the client
        // identity block above (clientFirstName/clientLastName) is an unbroken run of Strings, so
        // slotting another String into it would let a future reordering of either construction
        // site silently swap the client's avatar with a name. Wedged after a UUID and at the end
        // of the list, any such slip fails to compile instead.
        @Schema(types = {"string", "null"}, nullable = true,
                description = "The booking client's profile photo — the same already-public "
                        + "Cloudflare R2 object URL served by masterAvatarUrl and every other "
                        + "avatar field in this API (never a signed URL, never a raw storage key). "
                        + "Lets a provider timeline render the client's photo instead of a generic "
                        + "glyph. NULL in two cases, both of which must render the fallback glyph: "
                        + "(1) a guest (LINK) booking, which has no registered account at all "
                        + "(client_id IS NULL, V89 chk_bookings_guest_fields) and therefore no "
                        + "photo and no fallback — unlike clientFirstName/clientLastName, which do "
                        + "fall back to the OTP-verified guest name; (2) a registered client who "
                        + "has never uploaded one. Do not distinguish the two client-side. Both "
                        + "causes mean strictly 'this booking has no client photo' — NULL here "
                        + "never encodes who is asking. The value depends only on the booking, so "
                        + "the same booking yields the same value on GET /bookings/{id} and on "
                        + "every row of GET /bookings/me, for a provider and for the client "
                        + "themselves alike; a client reading their own booking sees their own "
                        + "photo. Safe to cache by booking id across both endpoints.")
        String clientAvatarUrl
) {

    /**
     * Builds the enriched detail view for the single-entity path. The caller (the service)
     * must supply {@code canReview} (the COMPLETED + no-existing-review predicate) and the
     * resolved discovery locality labels — neither is derivable from the entity graph.
     *
     * <p>The caller MUST have hydrated the full graph (client, master.user, master.salon,
     * masterService.serviceDefinition) — e.g. via {@code BookingRepository.findByIdWithFullGraph}
     * or {@code findAllByIdsWithGraph}, both of which carry
     * {@code JOIN FETCH b.masterService ms JOIN FETCH ms.serviceDefinition} — so the field reads
     * below trigger no lazy SELECTs. That two-hop chain remains load-bearing for
     * {@code serviceDefinition.name}/{@code .category}: a caller that hydrates the booking WITHOUT
     * it N+1s (or throws {@code LazyInitializationException} on a detached entity). Fix the query,
     * never weaken this contract. {@code priceMaxAtBooking} does NOT contribute to that
     * requirement — since V119 it is a frozen column on the booking row itself, not a walk into
     * the current service definition.
     *
     * <p>The master's discovery address (salon vs own-user) is resolved by the salon-primary
     * rule: a salon-employed master surfaces the salon's name + street/building; an independent
     * master surfaces no salon name and the master's own street/building.
     */
    public static BookingDetailResponse from(
            Booking booking,
            boolean canReview,
            String cityLabel,
            String districtLabel
    ) {
        Master master = booking.getMaster();
        User masterUser = master.getUser();
        Salon salon = master.getSalon();
        User client = booking.getClient();

        String resolvedStreet = salon != null ? salon.getStreet() : masterUser.getStreet();
        String resolvedBuildingNo = salon != null ? salon.getBuildingNo() : masterUser.getBuildingNo();
        String resolvedLocationNote = salon != null ? salon.getLocationNote() : masterUser.getLocationNote();

        return new BookingDetailResponse(
                booking.getId(),
                // Guest (LINK) bookings have no registered client (V89 chk_bookings_guest_fields) —
                // clientId is null for them, mirroring BookingResponse.from's guard.
                client != null ? client.getId() : null,
                master.getId(),
                booking.getMasterService().getId(),
                booking.getMasterService().getServiceDefinition().getName(),
                booking.getStatus(),
                booking.getStartsAt().atZoneSameInstant(TimeZones.KYIV),
                booking.getEndsAt().atZoneSameInstant(TimeZones.KYIV),
                booking.getPriceAtBooking(),
                booking.getPriceMaxAtBooking(),
                booking.getDurationMinutesAtBooking(),
                booking.getCreatedAt().atOffset(ZoneOffset.UTC),
                // Fall back to the OTP-verified guest identity so the owning provider still sees
                // a name on their calendar instead of null — guestPhone is intentionally excluded.
                client != null ? client.getFirstName() : booking.getGuestName(),
                client != null ? client.getLastName() : booking.getGuestSurname(),
                masterUser.getFirstName(),
                masterUser.getLastName(),
                masterUser.getProfessionalTitle(),
                booking.getClientComment(),
                booking.getProviderComment(),
                booking.getClientCancellationNote(),
                masterUser.getAvatarUrl(),
                masterUser.getRole(),
                salon != null ? salon.getName() : null,
                cityLabel,
                districtLabel,
                resolvedStreet,
                resolvedBuildingNo,
                resolvedLocationNote,
                booking.getMasterService().getServiceDefinition().getCategory(),
                canReview,
                // appointment is a LAZY @ManyToOne on a nullable FK — the identifier is served off
                // the proxy (or resolved as null) from the booking row itself, so this reads with no
                // extra SELECT and no widening of findByIdWithFullGraph / findAllByIdsWithGraph.
                booking.getAppointment() != null ? booking.getAppointment().getId() : null,
                // Same null-guard shape as clientId above, but with NO guest fallback — a guest
                // booking has no account and so no photo. Costs nothing: every caller of this
                // factory hydrates the booking through findByIdWithFullGraph /
                // findAllByIdsWithGraph, both of which already LEFT JOIN FETCH b.client for the
                // name reads above, so avatarUrl is a scalar off an already-materialised User row
                // — no extra statement, no widening of either fetch graph.
                client != null ? client.getAvatarUrl() : null
        );
    }
}
