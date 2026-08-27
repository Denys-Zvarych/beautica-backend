package com.beautica.booking.dto;

import com.beautica.auth.Role;
import com.beautica.booking.domain.BookingClosureRule;
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
 * <p><b>{@code canReview}</b> is TRUE for an unreviewed booking that is either {@code COMPLETED}
 * or {@code CONFIRMED} with an already-elapsed {@code endsAt} — the STATUS+TIME half is {@link
 * com.beautica.booking.domain.BookingClosureRule#isReviewEligible}, the single canonical
 * definition shared with the write-path gate ({@code ReviewService#createReview}) so a client
 * offered this CTA can never get a 400 from {@code POST /reviews}. Locked product decision: a
 * booking that entered the client's "Past" tab BY ELAPSED TIME is reviewable even when the
 * provider never marked it {@code COMPLETED} — see {@code BookingClosureRule#isReviewEligible}'s
 * javadoc for the full rationale. Computed by the service, never derivable from the entity graph
 * alone.
 *
 * <p><b>{@code locationNote} (client mobile phase 14.3 enrichment)</b> is the provider's
 * free-text arrival hint ("3-й поверх, код 1234"). It follows the EXACT SAME salon-vs-
 * independent resolution rule as {@code street}/{@code buildingNo} above — never a second,
 * parallel rule: a booking made AT A SALON surfaces that salon's own {@code locationNote}, a
 * booking made with an independent master surfaces the master's own user row's
 * {@code locationNote}. Since phase 242 the salon in question is the BOOKING's snapshot, not the
 * master's live affiliation. Nullable — most providers never write one.
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
 *
 * <p><b>{@code providerCanReviewClient}</b> (extends track 27.x / Phase 27.5) — the PROVIDER-side
 * mirror of {@code canReview}, gating the "Залишити відгук про клієнта" CTA on
 * {@code GET /bookings/{id}} AND on every provider row of {@code GET /bookings/me} (see below).
 * {@code true} iff ALL of: (1) the CURRENT authenticated viewer has provider review-authority over THIS
 * booking, computed by {@code AuthorizationService#hasProviderAuthorityOverBooking(UUID, Booking)}
 * — byte-for-byte the predicate {@code enforceCanReviewClient} throws on inside
 * {@code ClientReviewService.create}, so this flag and the service-layer arm of
 * {@code POST /client-reviews} can never disagree; (2)
 * {@link com.beautica.booking.domain.BookingClosureRule#isProviderReviewEligible} —
 * {@code status == COMPLETED}, STRICTLY. Unlike the client-side {@code canReview} flag (which uses
 * {@link com.beautica.booking.domain.BookingClosureRule#isReviewEligible} and also admits an
 * elapsed-but-unclosed {@code CONFIRMED} booking), this direction does NOT extend to that shape:
 * the provider controls their own closing action ({@code PATCH .../complete}), so offering the
 * review CTA before that action lets them rate a visit they have not yet attested happened — see
 * {@code BookingClosureRule#isProviderReviewEligible}'s javadoc for the full rationale; (3) the
 * booking has a real client (not a guest/LINK booking); (4) no {@code ClientReview} already
 * exists for this booking. A CLIENT or
 * SALON_MASTER viewer, or a provider with no authority over this specific booking, always reads
 * {@code false} here — never a thrown exception; the viewer either sees the detail (already gated
 * by {@code enforceCanViewBooking}) with this flag honestly {@code false}, or never reaches this
 * DTO at all. TWO callers compute this for real, through ONE shared conjunction
 * ({@code BookingService#providerCanReviewClient}): {@code BookingService#getBooking} (per-row
 * inputs) and {@code BookingService#listProviderBookings} (the provider {@code GET /bookings/me}
 * rows, same conjunction fed from page-scoped batched lookups — see
 * {@code BookingService#loadProviderReviewBatch}). The listing hardcoded {@code false} until the
 * archive-CTA fix; that was worse than omitting the field, because a client mapping it faithfully
 * saw a constant and had to gate its CTA on {@code status} alone, which then could not clear once
 * the provider had left feedback. Do not reintroduce a constant on any surface a client renders.
 * The REMAINING construction sites still pass {@code false} and are still sound: {@code
 * enrichCreated} and {@code rescheduleBooking} (a freshly-created or just-rescheduled booking is
 * always {@code CONFIRMED}, never {@code COMPLETED}, so it can never satisfy {@code
 * isProviderReviewEligible}) and the CLIENT projection path (its viewer is always CLIENT —
 * structurally excluded from provider authority). See each of those sites' own comment before
 * "optimising" this away.
 *
 * <p><b>Precisely how far the "agrees with the write endpoint" claim reaches (phase-27.x security
 * audit, LOW 3).</b> An earlier revision of this javadoc asserted the flag "can never disagree" with
 * {@code @authz.canReviewClient} as well. That is NOT true in general, and the weaker claim above is
 * the accurate one. {@code POST /client-reviews} is gated TWICE — the SpEL {@code @PreAuthorize}
 * {@code @authz.canReviewClient} and then {@code enforceCanReviewClient} in the service — and the
 * two derive "is this an independent-master booking" from different sources. The SpEL arm reads the
 * {@code BookingCompletionAccess} projection and takes {@code salonId == null}; the entity arm (and
 * therefore this flag) takes {@code masterType == INDEPENDENT_MASTER}. Those coincide on every
 * production-normal row but not on all of them, because {@code masters.salon_id} is
 * {@code ON DELETE SET NULL} ({@code V4__Patch_salons_add_masters.sql:13}) and neither the schema nor
 * {@code Master} constrains {@code master_type} against it: a salon-typed master whose salon row was
 * deleted has {@code salonId == null} with {@code masterType != INDEPENDENT_MASTER}, so the SpEL arm
 * grants that master authority over their own booking while this flag reads {@code false}. What
 * still holds — and is what the CTA depends on — is that the endpoint accepts only the CONJUNCTION
 * of both arms, so a viewer this flag shows {@code true} to is never rejected on authority grounds
 * by the service arm, and the divergent row above is fail-closed for the UI (no CTA offered, and
 * none would have succeeded). The mirror case (an {@code INDEPENDENT_MASTER}-typed master carrying a
 * non-null {@code salon_id}) would invert that and over-offer the CTA into a 403; no writer produces
 * that shape today, but nothing structurally forbids it either.
 *
 * <p><b>Why the two derivations were NOT unified.</b> Unifying means putting {@code masterType} into
 * {@code BookingCompletionAccess} and switching the projection-based predicates to it. That
 * projection is shared by six authorization paths ({@code canCompleteBooking},
 * {@code canCancelBooking}, {@code canRescheduleBooking}, {@code canReviewClient},
 * {@code canRescheduleAppointment}, {@code enforceCanManageAppointment}), several with no
 * entity-based twin to backstop them, and on the divergent row above it would REVOKE write authority
 * those gates grant today. That is an authorization behaviour change, out of scope for a
 * documentation-accuracy finding; it needs its own phase, with the product question "may a master
 * whose salon was deleted still close out their own bookings?" answered first. The javadoc was
 * softened instead.
 *
 * <p><b>Phase B1 — {@code masterAvgRating}/{@code masterReviewCount}.</b> The master's public
 * rating aggregate, surfaced so the client's booking-detail and leave-feedback screens can render
 * the provider's score without a second round-trip to {@code GET /masters/{id}} or
 * {@code /reviews/summary}. Both are read STRAIGHT OFF the already-loaded master row — the
 * denormalized {@code masters.avg_rating} / {@code masters.review_count} columns (V4), maintained
 * on write by {@code ReviewRepository#recalculateMasterRating} under the project's standing
 * "recalculate on write, read persisted on read" contract. Neither is aggregated at read time, so
 * neither adds a query, a join, or an N+1 on any path — see {@link #masterAvgRatingOrNull} for the
 * zero-review normalisation both mapper paths share.
 *
 * <p><b>Phase B2 — {@code salonId}.</b> The booking's own {@code salon_id} snapshot, exposed so the
 * mobile client can invalidate its salon-scoped review caches once a review lands (the salon half of
 * the master fan-out shipped in mobile {@code 848e8929}). It is deliberately read from
 * {@code booking.getSalon()} and NOT from {@code master.getSalon()}: {@code ReviewService#createReview}
 * stamps the review with {@code .salon(booking.getSalon())} and publishes {@code ReviewCreatedEvent}
 * with that same id, so the booking's snapshot — not the master's current affiliation — is the salon
 * whose {@code avg_rating}/{@code review_count} actually moved. A {@code master.salon}-derived id
 * would point at the wrong salon for any booking made before a salon rotation.
 *
 * <p><b>Phase 242 — {@code salonId} and the whole address block now share ONE source, and cannot
 * disagree.</b> B2 originally left {@code salonName} plus the
 * {@code street}/{@code buildingNo}/{@code locationNote}/{@code cityLabel}/{@code districtLabel}
 * block resolving off {@code master.getSalon()} — the master's LIVE affiliation — while
 * {@code salonId} came from the booking. That divergence was a PII leak, not a nuance: after a
 * master rotates salons, a client opening an OLD booking was served the NEW salon's
 * {@code locationNote}, which by its own {@code @Schema} contract holds premises-access
 * information («3-й поверх, код 1234») for a salon the client has never booked at. Both mapper
 * paths now resolve the entire block from {@code booking.getSalon()} ({@link #from} below, and the
 * projection's {@code LEFT JOIN b.salon s}), so {@code salonId != null} and
 * {@code salonName != null} are now the same predicate.
 *
 * <p><b>Scope of that fix — it closed the SALON branch of the ternary only (phase-242 audit,
 * finding 5).</b> Do not read the paragraph above as "the divergence is gone" outright; the same
 * defect class still exists on the OTHER half. When {@code booking.getSalon()} is {@code null} —
 * an independent-master booking — both mapper paths fall through to the master's LIVE {@code users}
 * row ({@link #from}'s {@code masterUser.getStreet()} / {@code getBuildingNo()} /
 * {@code getLocationNote()}, and the projection's {@code ELSE mu.X}). A solo master who moves house
 * therefore serves their NEW street and NEW door code on every OLD booking, exactly as a rotating
 * salon master used to. There is no per-booking address snapshot for that case to read instead:
 * {@code bookings} carries {@code salon_id} but no denormalised address columns, so closing it
 * would need a schema change and a product decision about what an old booking should show. It is
 * pre-existing, was NOT in scope of phase 242, and is recorded here rather than silently implied to
 * be handled. If you are auditing this DTO for the rotation leak, this is the residual.
 * <p><b>What did NOT change, and must not:</b> the salon-vs-independent precedence is still a
 * strict {@code salon != null ? salon.getX() : masterUser.getX()} ternary (and its
 * {@code CASE WHEN s.id IS NOT NULL} twin in the projection) — never {@code COALESCE(s.X, mu.X)}.
 * {@code COALESCE} falls through to the master's PERSONAL row whenever the salon's own column is
 * {@code NULL}, which is the HIGH regression this block already guards against (see
 * {@code ClientBookingDetailProjection}'s javadoc). Phase 242 changed only WHICH salon the
 * predicate keys off, never the predicate. A booking made at a salon always carries a non-null
 * {@code bookings.salon_id} (the column has existed since {@code V18__create_bookings.sql:8}, and
 * every write site stamps it at creation), so the fallthrough to the master's own row fires
 * exactly when the visit genuinely was at the master's own address — an independent-master
 * booking, including one made before the master later joined a salon.
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
                        + "salon-vs-independent rule as street/buildingNo, against the salon THIS "
                        + "BOOKING was made at (bookings.salon_id): a salon booking surfaces that "
                        + "salon's own note — never the master's current salon's, should the "
                        + "master have moved since — and an independent-master booking surfaces "
                        + "the master's own note. Nullable — most providers never set one.")
        String locationNote,
        @Schema(types = {"string", "null"}, nullable = true,
                description = "The RAW category slug (e.g. \"NAIL_SERVICE\"), sourced directly from "
                        + "service_definitions.category — NOT a human-readable display name, despite "
                        + "the field name. The Ukrainian display text lives in "
                        + "platform_categories.display_name, which no booking read path joins. Kept "
                        + "for backward compatibility with existing consumers; do not rename or "
                        + "repoint it to the display name without a coordinated client migration — "
                        + "that would silently change every current consumer's rendered value. New "
                        + "code that needs a stable machine key for icon/category resolution should "
                        + "prefer categoryKey below.")
        String categoryName,
        boolean canReview,
        @Schema(description = "TRUE only for the CURRENT authenticated viewer, and only on "
                + "GET /bookings/{id}: the viewer has provider review-authority over this "
                + "booking, the booking is COMPLETED (strictly — unlike the client-side canReview "
                + "flag, an elapsed-but-unclosed CONFIRMED booking does NOT qualify here; see "
                + "BookingClosureRule#isProviderReviewEligible), it has a real (non-guest) "
                + "client, and no ClientReview exists for it yet. FALSE for a CLIENT/SALON_MASTER viewer, an "
                + "unauthorized provider, or any row of the CLIENT listing path of "
                + "GET /bookings/me (which hardcodes false). The PROVIDER rows of "
                + "GET /bookings/me carry the real per-row value — see "
                + "BookingDetailResponse's class javadoc. Gates the \"Залишити відгук про "
                + "клієнта\" CTA; the write endpoint (POST /client-reviews) re-checks the same "
                + "conditions server-side regardless of this value.")
        boolean providerCanReviewClient,
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
        String clientAvatarUrl,
        @Schema(description = "Derived, read-time-only (Phase 29.1/29.2) — TRUE when this "
                + "booking's status is still CONFIRMED but its endsAt has already elapsed: no "
                + "scheduled job ever transitions such a booking to a terminal state, so this "
                + "flags the ones the provider still needs to close via /complete, "
                + "/not-complete or /decline. NEVER persisted, NEVER cached — recomputed on "
                + "every read from (status, endsAt, the current instant). NOT orthogonal to "
                + "canReview since the review-eligibility widening: an elapsed-but-unclosed "
                + "CONFIRMED booking reads TRUE here AND (when it has a registered client and no "
                + "existing review) TRUE for canReview too — closure-awaiting and review-eligible "
                + "now deliberately overlap for exactly this row shape, by locked product "
                + "decision (a booking that entered the client's Past tab by elapsed time is "
                + "reviewable even before the provider closes it — see BookingClosureRule#"
                + "isReviewEligible). The same for every row of GET /bookings/me and for GET "
                + "/bookings/{id} — a pure function of the booking, not of the viewer.")
        boolean awaitingClosure,
        // Phase B1. Appended LAST for the same reason as priceMaxAtBooking / appointmentId /
        // clientAvatarUrl above (see their comments): a pure append shifts no existing positional
        // argument at any of the three construction sites, and the pair sits behind a boolean with
        // two mutually distinct types (BigDecimal, Integer), so no reordering slip can survive
        // compilation.
        @Schema(types = {"number", "null"}, nullable = true,
                description = "The master's public average rating, 1.00-5.00, read off the "
                        + "denormalized masters.avg_rating column — the SAME value served by GET "
                        + "/masters/{id} and GET /masters/{id}/reviews/summary, never independently "
                        + "re-aggregated. Agreement across those three endpoints is exact, not "
                        + "eventual: GET /masters/{id} is served from the 5-minute 'master-detail' "
                        + "cache, and ReviewEventListener#onReviewCreated evicts that entry by "
                        + "masterId once the rating recalculation commits, so a client that leaves "
                        + "a review sees the new average on the booking AND on the profile on the "
                        + "very next request. NULL when masterReviewCount is 0: the column stores "
                        + "0.00 for an unreviewed master (V4 NOT NULL DEFAULT 0.00, and "
                        + "recalculateMasterRating's COALESCE(AVG(...), 0)), which is a storage "
                        + "artefact, not a rating — rendering it would show a brand-new master a "
                        + "damning zero stars. Render the 'no reviews yet' state when null; never "
                        + "substitute 0.")
        BigDecimal masterAvgRating,
        @Schema(types = {"integer", "null"}, nullable = true,
                description = "How many reviews the master's average is computed from. 0 for an "
                        + "unreviewed master (a true fact, unlike a 0.00 average) and non-null on "
                        + "every path that serves this DTO today; typed nullable so a client "
                        + "treats an absent value as 'unknown' rather than 'zero reviews'.")
        Integer masterReviewCount,
        // Phase B2. Appended LAST for the same reason as every field above it. NOTE the type
        // adjacency: this UUID follows an Integer and a BigDecimal, so a transposition with either
        // fails to compile — but if a future field of type UUID is appended alongside it, that
        // protection lapses and the pair must be checked by hand.
        @Schema(types = {"string", "null"}, format = "uuid", nullable = true,
                description = "The salon this booking was made AT, as snapshotted on the booking "
                        + "row (bookings.salon_id). NULL for an INDEPENDENT_MASTER booking. Exists "
                        + "so a client can invalidate its own salon-scoped caches after leaving a "
                        + "review: ReviewService#createReview stamps the review with "
                        + "booking.getSalon() and ReviewEventListener recalculates THAT salon's "
                        + "avg_rating/review_count, so this is the id whose aggregates moved. As "
                        + "of phase 242 salonName and the street/buildingNo/locationNote/cityLabel/"
                        + "districtLabel block are resolved from this SAME booking snapshot, so "
                        + "salonId != null and salonName != null are one predicate and the id "
                        + "always identifies the premises whose address is displayed alongside it. "
                        + "(Before 242 the address block came from the master's LIVE salon and the "
                        + "two could disagree after a rotation — that divergence is gone.)")
        UUID salonId,
        // Appended LAST, after the UUID salonId, for the same compile-time-slip-detection reason
        // as every field above it — mobile category-icon wiring, this session. Sourced from the
        // SAME sd.category value as categoryName above (never a second lookup), so the two can
        // never disagree; see categoryKeyOrNull's javadoc for the exact derivation, which mirrors
        // ClientAggregationRepository#findTimeline's categoryKey/categoryName pair (Beauty
        // Timeline) — same field names, same slug normalisation — EXCEPT that findTimeline's
        // sentinel-on-null ("UNKNOWN", so an uncategorised timeline row still renders a tile) is
        // deliberately NOT reused here: a booking card must render NO icon for an uncategorised
        // service, and a non-null placeholder would paint the wrong one. Null, never a fallback
        // string.
        @Schema(types = {"string", "null"}, nullable = true,
                description = "Stable machine key for the client-side category-icon resolver — the "
                        + "uppercase slug of the service's category (e.g. \"NAIL_SERVICE\"), or null "
                        + "when the service has no category. Mirrors "
                        + "ClientAggregationRepository#findTimeline's categoryKey/categoryName pair "
                        + "(Beauty Timeline). Prefer this over categoryName for icon resolution — "
                        + "categoryName is for display only. Never a fallback/placeholder value: a "
                        + "null here must render no icon, not a guessed one.")
        String categoryKey
) {

    /**
     * The single zero-review normalisation for {@code masterAvgRating}, shared verbatim by BOTH
     * mapper paths (the entity {@link #from} below and {@code BookingService#toDetailResponse}'s
     * CLIENT projection path) so the two independently maintained mappers cannot compute this
     * field differently — the exact divergence class {@code BookingDetailContractIT}'s reflective
     * parity loop exists to catch, and which once leaked a master's personal {@code locationNote}.
     *
     * <p>The rule mirrors {@code ReviewService#getMasterReviewSummary}'s
     * {@code master.getReviewCount() == 0 ? null : master.getAvgRating()} exactly, so a client
     * reading a master's rating on a booking and on {@code /reviews/summary} can never see two
     * different values.
     *
     * <p>Phase 240 audit (Findings 2/3): the master-side response DTOs call this same method
     * rather than re-implementing the branch — {@link com.beautica.master.dto.MasterDetailResponse},
     * {@link com.beautica.master.dto.MasterSummaryResponse} and {@link BookableMasterResponse} all
     * used to emit the raw stored {@code 0.00}, so an unreviewed master rendered "no reviews yet"
     * on a booking and a phantom "0.0 stars" on their own profile. One method, one branch, every
     * master-rating surface. Not moved to {@code common/} because the projection path needs the
     * {@code (int, BigDecimal)} primitive form, which is what this signature already is.
     *
     * <p>It is needed because {@code masters.avg_rating} is {@code NOT NULL DEFAULT
     * 0.00} (V4) and {@code recalculateMasterRating} writes {@code COALESCE(AVG(...), 0)} — so an
     * unreviewed master stores a literal {@code 0.00} that must NOT reach the wire as a rating.
     *
     * <p>Deliberately NOT expressed as a JPQL {@code CASE WHEN} inside the projection query: the
     * projection selects the raw column pair and normalises here, in Java, so both paths run the
     * same branch rather than a JPQL copy and a Java copy that can drift apart.
     *
     * @param reviewCount the master's persisted {@code review_count}
     * @param storedAvgRating the master's persisted {@code avg_rating} (never null in the DB)
     * @return {@code null} when the master has no reviews, else the stored average
     */
    public static BigDecimal masterAvgRatingOrNull(int reviewCount, BigDecimal storedAvgRating) {
        return reviewCount == 0 ? null : storedAvgRating;
    }

    /**
     * The single {@code categoryKey} derivation, shared verbatim by BOTH mapper paths ({@link #from}
     * below and {@code BookingService#toDetailResponse}'s CLIENT projection path) — for the same
     * divergence-prevention reason as {@link #masterAvgRatingOrNull}, and covered automatically by
     * {@code BookingDetailContractIT}'s reflective parity loop the moment this component exists.
     *
     * <p>Mirrors {@code ClientAggregationRepository#findTimeline}'s {@code categoryKey} slug
     * normalisation (uppercase, non-alphanumeric runs collapsed to {@code _}, trimmed) — the exact
     * transform {@code ClientPassportService#categoryKey} applies for the Beauty Timeline — so a
     * service's category resolves to the SAME machine key on the timeline and on a booking card.
     * The one deliberate difference: {@code findTimeline} returns the sentinel {@code "UNKNOWN"} for
     * a null/blank category (so an uncategorised row still renders a timeline tile); this method
     * returns {@code null} instead, because a booking card must render NO icon for an uncategorised
     * service — a non-null placeholder here would paint the wrong glyph, not a generic one.
     *
     * @param category the raw {@code service_definitions.category} slug (nullable) — the SAME value
     *                  passed as {@code categoryName} at both call sites, never a second read
     * @return the normalised uppercase slug, or {@code null} when {@code category} is null/blank
     */
    public static String categoryKeyOrNull(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        return category.trim()
                .toUpperCase(java.util.Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    /**
     * Builds the enriched detail view for the single-entity path. The caller (the service)
     * must supply {@code canReview} (see this class's javadoc for the full predicate),
     * {@code providerCanReviewClient} (the viewer-aware provider-side mirror — see this class's
     * javadoc), and the resolved discovery locality labels — none of the three is derivable from
     * the entity graph alone. {@code now} is the request-scoped absolute instant (Phase 29.2)
     * threaded down to {@link com.beautica.booking.domain.BookingClosureRule#isAwaitingClosure} —
     * an already-resolved {@code clock.instant()}, never re-derived here and never {@link
     * java.time.OffsetDateTime#now()}.
     *
     * <p>The caller MUST have hydrated the full graph (client, master.user, <b>booking.salon</b>,
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
     * <p>The discovery address (salon vs own-user) is resolved by the salon-primary rule against
     * the BOOKING's own salon snapshot (phase 242): a booking made at a salon surfaces that
     * salon's name + street/building/note; an independent-master booking surfaces no salon name
     * and the master's own street/building/note. {@code booking.salon} — not {@code master.salon}
     * — is therefore the association the fetch graph must carry, and both graph queries above were
     * re-pointed to {@code LEFT JOIN FETCH b.salon} in the same change: these are real property
     * reads ({@code getName()}, {@code getStreet()}, {@code getLocationNote()}) that INITIALISE
     * the proxy, unlike the identifier-only read {@code salonId} used to be.
     */
    public static BookingDetailResponse from(
            Booking booking,
            boolean canReview,
            boolean providerCanReviewClient,
            String cityLabel,
            String districtLabel,
            OffsetDateTime now
    ) {
        Master master = booking.getMaster();
        User masterUser = master.getUser();
        // Phase 242 — the BOOKING's own salon snapshot (bookings.salon_id), NEVER
        // master.getSalon() (the master's LIVE affiliation). See this class's javadoc: after a
        // salon rotation the master's current salon is a different premises the client never
        // booked at, and its locationNote is by contract a door code.
        Salon salon = booking.getSalon();
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
                providerCanReviewClient,
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
                client != null ? client.getAvatarUrl() : null,
                BookingClosureRule.isAwaitingClosure(booking.getStatus(), booking.getEndsAt(), now),
                // Phase B1 — both are scalars off the SAME `master` row already materialised above
                // for master.getId()/getUser()/getSalon(). Every caller of this factory hydrates the
                // booking through findByIdWithFullGraph / findAllByIdsWithGraph, both of which carry
                // `JOIN FETCH b.master m`, so these two reads add no statement and require no
                // widening of either fetch graph. They are denormalized columns (V4), NOT a live
                // aggregate — do not "fix" this into a count/avg query.
                masterAvgRatingOrNull(master.getReviewCount(), master.getAvgRating()),
                master.getReviewCount(),
                // Phase B2 introduced this field off `booking.getSalon()` while `salon` above was
                // still `master.getSalon()`; phase 242 re-pointed the whole address block onto the
                // same booking snapshot, so this is now simply the id of the `salon` local. Kept as
                // its own expression rather than folded into `salon` only because the null-guard
                // reads clearer here; the two can no longer disagree.
                salon != null ? salon.getId() : null,
                // Derived from the SAME serviceDefinition.getCategory() read as categoryName above
                // — no second lazy-graph walk, no widening of this factory's documented hydration
                // contract. See categoryKeyOrNull's javadoc.
                categoryKeyOrNull(booking.getMasterService().getServiceDefinition().getCategory())
        );
    }
}
