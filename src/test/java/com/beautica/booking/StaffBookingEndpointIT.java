package com.beautica.booking;

import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.service.NotificationOutboxService;
import com.beautica.notification.sms.SmsService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Phase 22.4 — {@code POST /api/v1/masters/&#123;masterId&#125;/bookings} over the full HTTP stack:
 * real Spring Security method security, the real {@code AuthorizationService}, the real
 * {@code StaffBookingScopeResolver} and a real Testcontainers Postgres.
 *
 * <p><b>What this suite adds over the slices.</b> The slices mock the predicate and the resolver, so
 * they prove the wiring but not the verdicts. Here every row of the amendment-A1 matrix is decided
 * by live rows in {@code salons} / {@code masters} / {@code users}, and the created booking has to
 * satisfy {@code chk_bookings_guest_fields} (V137), {@code chk_bookings_source} and the
 * {@code no_overlapping_bookings} GIST EXCLUDE. {@code created_by_user_id} is asserted off the row
 * itself — it is the ONLY attribution a staff booking carries.
 *
 * <p><b>Live clock, deliberately.</b> {@code JwtTokenProvider} mints token expiry off the injected
 * {@code Clock}, but JJWT validates against its own system clock, so a frozen-clock context (the
 * shape {@code StaffBookingIT} uses, which calls the service directly and needs no token) would
 * issue tokens that are already expired at parse time. The schedule is therefore seeded for every
 * ISO weekday and the start is derived as "tomorrow 12:00 Kyiv", which is grid-aligned, inside
 * working hours and in the future regardless of when the suite runs. No test here pins a clock, so
 * every time read is live — coherent, not mixed.
 *
 * <p><b>Where the cross-salon row is decided, and why it 403s at the gate.</b> An owner of another
 * salon is rejected by {@code @authz.canBookForMaster} before the handler runs, so 22.2's
 * {@code assertMasterInScope} never sees the request. That is correct defence in depth — the
 * scope check is a floor, not the gate — and it is why the falsification of the {@code InSalon}
 * derivation lives in {@code StaffBookingScopeResolverTest} (the resolver must never be ABLE to
 * emit a salon it was not handed by an actor-keyed lookup) rather than here.
 *
 * <p>Fixture data uses no occupied-territory locality references.
 */
@Import(TestSecurityConfig.class)
@DisplayName("StaffBookingEndpointIT — POST /masters/{masterId}/bookings over HTTP")
class StaffBookingEndpointIT extends AbstractStaffBookingIT {

    /**
     * Mocked at the INTERFACE, which is exactly the seam Phase 22.7's gate operates on: production
     * resolves {@code NoOpSmsService} or {@code TurbosmsService} here depending on
     * {@code app.booking.sms.enabled}, and no caller can tell which. Mocking it keeps this suite
     * about the ENDPOINT — no Turbosms HTTP stub, no dependence on the flag's value.
     */
    @MockBean
    private SmsService smsService;

    @MockBean
    private NotificationOutboxService notificationOutboxService;

    // ════════════════════════════════════════════════════════════════════════════════
    // The admitted rows
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Admitted callers")
    class Admitted {

        /**
         * The full row shape, asserted against the database rather than the response: the response
         * cannot show that {@code cancel_token} is null or that {@code client_id} was left unset,
         * and those are the columns {@code chk_bookings_guest_fields} constrains.
         */
        @Test
        @DisplayName("SALON_OWNER books their own salon's master → 201, CONFIRMED/STAFF walk-in row")
        void should_return201AndPersistStaffRow_when_ownerBooksTheirOwnMaster() {
            ResponseEntity<String> resp = create(salon.masterId(), tokenFor(salon.ownerEmail()), tomorrowAtNoon());

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

            Map<String, Object> row = onlyBooking();
            assertThat(row.get("status")).isEqualTo("CONFIRMED");
            assertThat(row.get("booking_source")).isEqualTo("STAFF");
            assertThat(row.get("created_by_user_id")).isEqualTo(salon.ownerId());
            assertThat(row.get("salon_id")).isEqualTo(salon.salonId());
            assertThat(row.get("client_id")).isNull();
            assertThat(row.get("cancel_token")).isNull();
            assertThat(row.get("guest_name")).isEqualTo("Марія");
            assertThat(row.get("guest_surname")).isEqualTo("Левченко");
            assertThat(row.get("guest_phone"))
                    .as("the staff-typed number is normalised to E.164 inside the service")
                    .isEqualTo(E164_PHONE);
        }

        @Test
        @DisplayName("assigned SALON_ADMIN books the same master → 201 (admin = owner)")
        void should_return201_when_assignedAdminBooksASalonMaster() {
            String adminEmail = insertUser("SALON_ADMIN", salon.salonId()).email();

            ResponseEntity<String> resp = create(salon.masterId(), tokenFor(adminEmail), tomorrowAtNoon());

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(onlyBooking().get("created_by_user_id"))
                    .as("attribution follows the acting admin, not the salon owner")
                    .isNotEqualTo(salon.ownerId());
            // No outbox row on ANY authorized path — StaffBookingService is explicit (see its class
            // Javadoc, "Not here") that inventing a NEW_BOOKING enqueue would risk double-notifying a
            // track whose provider-side copy is not yet decided. Pinned here too, not just on the
            // owner path, so the admin path cannot silently regress ahead of that decision.
            verifyNoInteractions(notificationOutboxService);
        }

        /**
         * Amendment A6's headline row. {@code salon_id} is null (an independent master has none) and
         * {@code created_by_user_id == masters.user_id} — the exact, queryable predicate that
         * distinguishes a self-booking from a staff-on-behalf booking WITHOUT a fourth
         * {@code BookingSource} value (amendment A7).
         */
        @Test
        @DisplayName("INDEPENDENT_MASTER books themselves → 201, salon_id null, created_by == own user")
        void should_return201_when_independentMasterBooksThemselves() {
            Independent solo = seedIndependentMaster();

            ResponseEntity<String> resp =
                    create(solo.masterId(), solo.masterServiceId(), tokenFor(solo.email()), tomorrowAtNoon());

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            Map<String, Object> row = onlyBooking();
            assertThat(row.get("salon_id")).isNull();
            assertThat(row.get("created_by_user_id")).isEqualTo(solo.userId());
            assertThat(row.get("booking_source")).isEqualTo("STAFF");
            // The self-notify case: even though actor == recipient here, no outbox row is enqueued —
            // see the owner-path test below for why (StaffBookingService's "Not here" Javadoc).
            verifyNoInteractions(notificationOutboxService);
        }

        /**
         * Phase 22.7 turned the "no SMS" half of this assertion inside out, deliberately.
         *
         * <p>22.4 shipped no walk-in SMS at all, so this test pinned {@code verifyNoInteractions} on
         * both collaborators. 22.7 adds the send — but it adds it AT THE SEAM, so what changed is
         * that {@code SmsService#send} is now always called, while whether anything is actually
         * delivered is decided by which bean {@code SmsConfig} registered for
         * {@code app.booking.sms.enabled} (off by default ⇒ {@code NoOpSmsService}, which logs and
         * returns). Mocking the interface here is therefore the correct lens: it proves the call
         * site fires with the right recipient without asserting anything about delivery, which is
         * {@code SmsFeatureGateTest}'s and {@code WalkInBookingSmsIT}'s subject.
         *
         * <p>The notification half is UNCHANGED and still absolute: a staff booking enqueues no
         * outbox row in any configuration.
         */
        @Test
        @DisplayName("a successful staff create sends the walk-in SMS and enqueues no notification")
        void should_sendWalkInSmsAndNoNotification_when_staffBookingSucceeds() {
            ResponseEntity<String> resp = create(salon.masterId(), tokenFor(salon.ownerEmail()), tomorrowAtNoon());

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            // E.164, matching the guest_phone column — not the "050 123 45 67" that was posted.
            verify(smsService).send(eq(E164_PHONE), anyString());
            verifyNoInteractions(notificationOutboxService);
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // The multi-salon owner — the ONE branch of the resolver that reads the master
    // ════════════════════════════════════════════════════════════════════════════════

    /**
     * <b>Until this suite, {@code MasterRepository#findSalonIdByIdAndSalonOwnerId} had never been
     * executed.</b> The query is new in Phase 22.4 and its only other appearance in the codebase is
     * as a Mockito stub in {@code StaffBookingScopeResolverTest} — so its JPQL (the
     * {@code JOIN m.salon s}, the {@code s.owner.id = :ownerId} predicate that is the whole reason
     * the emitted scope is the caller's authority rather than the target's attribute, and the
     * {@code s.isActive = true} clause) was pinned only by the assumption that it parses. A typo in
     * any of the three would have shipped green: {@code @Query} JPQL is validated at context start,
     * but a WRONG-yet-valid predicate is not.
     *
     * <p>The shape below is the minimum that reaches the branch: the fixture owner already has one
     * salon with a working master, so adding a second EMPTY salon to the same owner takes
     * {@code findIdsByOwnerIdAndIsActiveTrue} from one row to two, which is the only condition
     * {@code ownedSalonId} splits on.
     */
    @Nested
    @DisplayName("Multi-salon owner")
    class MultiSalonOwner {

        @Test
        @DisplayName("books a master in the salon that actually employs them, not the owner's other salon")
        void should_return201AndScopeToTheEmployingSalon_when_theOwnerOwnsSeveralSalons() {
            UUID otherOwnedSalonId = seedExtraSalonFor(salon.ownerId());

            ResponseEntity<String> resp =
                    create(salon.masterId(), tokenFor(salon.ownerEmail()), tomorrowAtNoon());

            assertThat(resp.getStatusCode())
                    .as("a second owned salon must not make the route ambiguous — the master selects")
                    .isEqualTo(HttpStatus.CREATED);
            Map<String, Object> row = onlyBooking();
            assertThat(row.get("salon_id"))
                    .as("the scope is the owned salon employing this master")
                    .isEqualTo(salon.salonId());
            assertThat(row.get("salon_id"))
                    .as("and never the owner's other salon, which employs nobody")
                    .isNotEqualTo(otherOwnedSalonId);
            assertThat(row.get("created_by_user_id")).isEqualTo(salon.ownerId());
        }

        /**
         * The owner-scoped predicate lives INSIDE the query, so a multi-salon owner gets no more
         * reach than a single-salon one. The gate rejects this first — which is the point: this row
         * asserts that owning several salons does not open the branch as a side door.
         */
        @Test
        @DisplayName("still cannot reach a master of a salon they do not own")
        void should_reject403_when_aMultiSalonOwnerTargetsAForeignMaster() {
            seedExtraSalonFor(salon.ownerId());
            Salon foreign = seedSalon();

            ResponseEntity<String> resp =
                    create(foreign.masterId(), foreign.masterServiceId(),
                            tokenFor(salon.ownerEmail()), tomorrowAtNoon());

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(bookingCount()).isZero();
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // The denied rows — all 403, none distinguishable from another
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Denied callers")
    class Denied {

        @Test
        @DisplayName("owner of a DIFFERENT salon → 403, and nothing is written")
        void should_reject403_when_ownerOfAnotherSalonBooksThisMaster() {
            Salon other = seedSalon();

            ResponseEntity<String> resp = create(salon.masterId(), tokenFor(other.ownerEmail()), tomorrowAtNoon());

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(bookingCount()).isZero();
        }

        @Test
        @DisplayName("admin of a DIFFERENT salon → 403")
        void should_reject403_when_adminOfAnotherSalonBooksThisMaster() {
            Salon other = seedSalon();
            String foreignAdmin = insertUser("SALON_ADMIN", other.salonId()).email();

            ResponseEntity<String> resp = create(salon.masterId(), tokenFor(foreignAdmin), tomorrowAtNoon());

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(bookingCount()).isZero();
        }

        /**
         * The self-booking constraint, and the single most important assertion of this phase: the
         * target is a real, active, bookable independent master — everything except the identity
         * matches.
         */
        @Test
        @DisplayName("INDEPENDENT_MASTER booking ANOTHER master → 403")
        void should_reject403_when_independentMasterBooksSomeoneElse() {
            Independent me = seedIndependentMaster();
            Independent someoneElse = seedIndependentMaster();

            ResponseEntity<String> resp = create(
                    someoneElse.masterId(), someoneElse.masterServiceId(), tokenFor(me.email()), tomorrowAtNoon());

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(bookingCount()).isZero();
        }

        @Test
        @DisplayName("INDEPENDENT_MASTER booking a salon-bound master → 403")
        void should_reject403_when_independentMasterBooksASalonMaster() {
            Independent me = seedIndependentMaster();

            ResponseEntity<String> resp = create(salon.masterId(), tokenFor(me.email()), tomorrowAtNoon());

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(bookingCount()).isZero();
        }

        /** A salon master's calendar stays read-only — including for their OWN master profile. */
        @Test
        @DisplayName("SALON_MASTER booking their own profile → 403")
        void should_reject403_when_salonMasterBooksTheirOwnProfile() {
            Invited invited = seedInvitedMaster(salon);

            ResponseEntity<String> resp = create(invited.masterId, tokenFor(invited.email), tomorrowAtNoon());

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(bookingCount()).isZero();
        }

        @Test
        @DisplayName("CLIENT → 403")
        void should_reject403_when_callerIsAClient() {
            String clientEmail = insertUser("CLIENT", null).email();

            ResponseEntity<String> resp = create(salon.masterId(), tokenFor(clientEmail), tomorrowAtNoon());

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(bookingCount()).isZero();
        }

        /**
         * <b>403, not 404</b> — the ordering guarantee. Method security runs before the handler, so
         * 22.2's own (deliberately indistinct) 404 for an unknown master is unreachable over HTTP
         * and {@code masterId} cannot be probed for existence.
         */
        @Test
        @DisplayName("unknown masterId → 403, never 404")
        void should_reject403NotFound_when_masterDoesNotExist() {
            ResponseEntity<String> resp =
                    create(UUID.randomUUID(), tokenFor(salon.ownerEmail()), tomorrowAtNoon());

            assertThat(resp.getStatusCode())
                    .as("a 404 here would turn masterId into an existence oracle")
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        /**
         * <b>The row that actually holds the ordering guarantee, and it was missing.</b>
         *
         * <p>Mutation-proved during the Phase 22.4 QA pass: turning {@code canBookForMaster}'s whole
         * salon branch into {@code return true} left EVERY denied row in this suite green, because
         * 22.2's {@code assertMasterInScope} independently rejects each of them. That is the defence
         * in depth working exactly as designed — and it also means this suite gave no signal on the
         * gate at all.
         *
         * <p>One case escapes the second layer, and it is precisely the one this phase exists to
         * close. Drop {@code .filter(MasterBookability::isBookable)} from the gate and an INACTIVE
         * master is no longer stopped before the handler; the request reaches
         * {@code StaffBookingService}, whose own {@code isBookable} filter throws
         * {@code NotFoundException("Master not found or inactive")} — a <b>404</b>. The caller now
         * learns that the id exists but is deactivated, which is the existence oracle the uniform
         * 403 was built to erase. The unit tier pins the predicate's verdict; only this row pins
         * that the verdict lands BEFORE the handler.
         *
         * <p>The master here belongs to the CALLER's own salon: authority is not in question, only
         * bookability, so a 403 can come from nowhere else.
         */
        @Test
        @DisplayName("a deactivated master → 403, never 22.2's 404 — the ordering guarantee")
        void should_reject403NotFound_when_theTargetMasterIsDeactivated() {
            jdbcTemplate.update("UPDATE masters SET is_active = false WHERE id = ?", salon.masterId());

            ResponseEntity<String> resp = create(salon.masterId(), tokenFor(salon.ownerEmail()), tomorrowAtNoon());

            assertThat(resp.getStatusCode())
                    .as("a 404 here tells the caller the id exists — method security must run first")
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(bookingCount()).isZero();
        }

        /**
         * The companion row: {@code MasterBookability} treats a closed salon as unbookable even
         * though deactivating a salon does not cascade to {@code masters.is_active}, so an owner
         * cannot keep booking into a salon they have themselves closed. Also 403, for the same
         * no-oracle reason.
         */
        @Test
        @DisplayName("a master of a salon the owner has closed → 403, for that salon's own owner")
        void should_reject403_when_theMastersSalonHasBeenDeactivated() {
            jdbcTemplate.update("UPDATE salons SET is_active = false WHERE id = ?", salon.salonId());

            ResponseEntity<String> resp = create(salon.masterId(), tokenFor(salon.ownerEmail()), tomorrowAtNoon());

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(bookingCount()).isZero();
        }

        /**
         * <b>The no-oracle guarantee is about the BODY as much as the status.</b> Every test above
         * asserts {@code 403}, which leaves the response payload — the one thing a caller actually
         * reads — completely unpinned. Three structurally different denials are compared here:
         * the target does not exist at all; it exists and belongs to somebody else's salon; and the
         * caller's role can never book. Each takes a different path (an {@code Optional} miss, a
         * {@code hasManagementAccess} false, a role fast-path with no DB read), and the resolver has
         * yet a fourth message ({@code "Access denied"}) it could surface. If any of them ever
         * differed by a single character, {@code masterId} would become probeable again through the
         * body while every status assertion stayed green.
         */
        @Test
        @DisplayName("all three denial reasons return a byte-identical 403 body")
        void should_returnAnIdenticalForbiddenBody_when_theDenialReasonDiffers() {
            String clientEmail = insertUser("CLIENT", null).email();
            Salon other = seedSalon();

            String unknownMaster =
                    create(UUID.randomUUID(), tokenFor(salon.ownerEmail()), tomorrowAtNoon()).getBody();
            String foreignMaster =
                    create(salon.masterId(), tokenFor(other.ownerEmail()), tomorrowAtNoon()).getBody();
            String deniedRole =
                    create(salon.masterId(), tokenFor(clientEmail), tomorrowAtNoon()).getBody();

            assertThat(foreignMaster)
                    .as("\"does not exist\" and \"exists but is not yours\" must be indistinguishable")
                    .isEqualTo(unknownMaster);
            assertThat(deniedRole)
                    .as("and so must \"your role can never do this\"")
                    .isEqualTo(unknownMaster);
            assertThat(unknownMaster)
                    .as("the uniform body must not echo the probed id back")
                    .doesNotContain(salon.masterId().toString());
        }

        @Test
        @DisplayName("no token → 401")
        void should_reject401_when_anonymous() {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            ResponseEntity<String> resp = restTemplate.postForEntity(
                    url(salon.masterId()), new HttpEntity<>(body(tomorrowAtNoon()), headers), String.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // Domain rejections surfaced through the HTTP layer
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Domain rejections")
    class DomainRejections {

        @Test
        @DisplayName("a start outside working hours → 409")
        void should_reject409_when_startIsOutsideWorkingHours() {
            ResponseEntity<String> resp = create(
                    salon.masterId(), tokenFor(salon.ownerEmail()), tomorrowAt(LocalTime.of(6, 0)));

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(bookingCount()).isZero();
        }

        @Test
        @DisplayName("a second booking on the same window → 409")
        void should_reject409_when_theWindowIsAlreadyTaken() {
            String token = tokenFor(salon.ownerEmail());
            assertThat(create(salon.masterId(), token, tomorrowAtNoon()).getStatusCode())
                    .isEqualTo(HttpStatus.CREATED);

            ResponseEntity<String> resp = create(salon.masterId(), token, tomorrowAtNoon());

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(bookingCount()).isOne();
        }

        @Test
        @DisplayName("a service the master does not perform → 404")
        void should_reject404_when_serviceIsNotAssignedToThatMaster() {
            Independent other = seedIndependentMaster();

            ResponseEntity<String> resp = restTemplate.exchange(
                    url(salon.masterId()), org.springframework.http.HttpMethod.POST,
                    new HttpEntity<>(body(tomorrowAtNoon(), other.masterServiceId()),
                            bearerHeaders(tokenFor(salon.ownerEmail()))),
                    String.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(bookingCount()).isZero();
        }

        @Test
        @DisplayName("a non-Ukrainian phone → 400 with no input echoed back")
        void should_reject400_when_phoneIsNotUkrainian() {
            ResponseEntity<String> resp = restTemplate.exchange(
                    url(salon.masterId()), org.springframework.http.HttpMethod.POST,
                    new HttpEntity<>(bodyWithPhone(tomorrowAtNoon(), "+15551234567"),
                            bearerHeaders(tokenFor(salon.ownerEmail()))),
                    String.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(resp.getBody()).doesNotContain("15551234567");
            assertThat(bookingCount()).isZero();
        }

        @Test
        @DisplayName("a missing walk-in surname → 400 before any authorization-independent work")
        void should_reject400_when_surnameIsMissing() {
            String malformed = """
                    {"masterServiceIds":["%s"],"startsAt":"%s",
                     "guest":{"name":"Марія","phone":"%s"}}
                    """.formatted(salon.masterServiceId(), tomorrowAtNoon(), RAW_PHONE);

            ResponseEntity<String> resp = restTemplate.exchange(
                    url(salon.masterId()), org.springframework.http.HttpMethod.POST,
                    new HttpEntity<>(malformed, bearerHeaders(tokenFor(salon.ownerEmail()))),
                    String.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(bookingCount()).isZero();
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // The 201 body (Phase 22.14) — AppointmentDetailResponse, persisted AND returned
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Visit body")
    class VisitBody {

        @Test
        @DisplayName("owner books a salon master → the 201 body is the persisted visit")
        void should_persistAndReturnVisit_when_ownerBooksSalonMaster() throws Exception {
            ResponseEntity<String> resp = create(salon.masterId(), tokenFor(salon.ownerEmail()), tomorrowAtNoon());

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            JsonNode data = objectMapper.readTree(resp.getBody()).path("data");
            assertThat(data.path("items")).hasSize(1);
            UUID appointmentId = UUID.fromString(data.path("id").asText());
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM appointments WHERE id = ?", Integer.class, appointmentId))
                    .as("the id the response carries is a REAL, persisted header")
                    .isEqualTo(1);
            UUID bookingId = UUID.fromString(data.path("items").get(0).path("bookingId").asText());
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT appointment_id FROM bookings WHERE id = ?", UUID.class, bookingId))
                    .isEqualTo(appointmentId);
        }

        @Test
        @DisplayName("independent master books themselves → the 201 body is the persisted visit")
        void should_persistAndReturnVisit_when_independentMasterBooksSelf() throws Exception {
            Independent solo = seedIndependentMaster();

            ResponseEntity<String> resp =
                    create(solo.masterId(), solo.masterServiceId(), tokenFor(solo.email()), tomorrowAtNoon());

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            JsonNode data = objectMapper.readTree(resp.getBody()).path("data");
            assertThat(data.path("items")).hasSize(1);
            assertThat(data.path("salonName").isNull())
                    .as("an independent master's visit carries no salon")
                    .isTrue();
        }

        /**
         * The admin-of-another-salon 403 is already pinned at the STATUS/body-uniformity level by
         * {@code Denied#should_reject403_when_adminOfAnotherSalonBooksThisMaster} — not repeated here
         * verbatim; this suite's own contribution is the VISIT-shaped 201 body above, which is what
         * actually changed in this phase.
         */

        @Test
        @DisplayName("no service was a range → totalPriceMax is null")
        void should_returnTotalPriceMaxNull_when_noServiceWasARange() throws Exception {
            ResponseEntity<String> resp = create(salon.masterId(), tokenFor(salon.ownerEmail()), tomorrowAtNoon());

            JsonNode data = objectMapper.readTree(resp.getBody()).path("data");
            assertThat(data.path("totalPriceMax").isNull())
                    .as("a single-price visit renders totalPrice alone")
                    .isTrue();
            assertThat(new BigDecimal(data.path("totalPrice").asText())).isEqualByComparingTo(PRICE);
        }

        /**
         * The visit-total range rule (already proved for {@code AppointmentService} — BE-3) inherited
         * for FREE by the staff path via {@code enrich(...)} reuse, and must not be re-derived here:
         * one FIXED-price service (350.00) plus one RANGE service (200.00-400.00) sums to
         * {@code totalPrice = 550.00} and {@code totalPriceMax = 350.00 + 400.00 = 750.00} — the FIXED
         * item's own price contributes to the ceiling exactly as it does to the floor.
         */
        @Test
        @DisplayName("any service was a range → totalPriceMax is the summed ceiling")
        void should_returnSummedTotalPriceMax_when_anyServiceWasARange() throws Exception {
            UUID rangeService = insertRangeService(salon.masterId(), "SALON", salon.salonId(),
                    new BigDecimal("200.00"), new BigDecimal("400.00"));
            String body = """
                    {"masterServiceIds":["%s","%s"],"startsAt":"%s",
                     "guest":{"name":"Марія","surname":"Левченко","phone":"%s"}}
                    """.formatted(salon.masterServiceId(), rangeService, tomorrowAtNoon(), RAW_PHONE);

            ResponseEntity<String> resp = restTemplate.exchange(
                    url(salon.masterId()), org.springframework.http.HttpMethod.POST,
                    new HttpEntity<>(body, bearerHeaders(tokenFor(salon.ownerEmail()))), String.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            JsonNode data = objectMapper.readTree(resp.getBody()).path("data");
            assertThat(new BigDecimal(data.path("totalPrice").asText()))
                    .isEqualByComparingTo(new BigDecimal("550.00"));
            assertThat(data.path("totalPriceMax").isNull()).isFalse();
            assertThat(new BigDecimal(data.path("totalPriceMax").asText()))
                    .isEqualByComparingTo(new BigDecimal("750.00"));
        }

        /**
         * A RANGE-priced service, additive to {@link AbstractStaffBookingIT#insertService}: same
         * shape, plus {@code price_type = 'RANGE'} and a {@code price_max} ceiling.
         */
        private UUID insertRangeService(
                UUID masterId, String ownerType, UUID ownerId, BigDecimal basePrice, BigDecimal priceMax) {
            UUID serviceDefId = UUID.randomUUID();
            UUID serviceTypeId = resolveUnusedServiceTypeId(ownerType, ownerId);
            jdbcTemplate.update(
                    "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                            + "base_duration_minutes, base_price, price_type, price_max, "
                            + "buffer_minutes_after, is_active, created_at, updated_at) "
                            + "VALUES (?, ?, ?, 'Педикюр', ?, ?, ?, 'RANGE', ?, 0, true, NOW(), NOW())",
                    serviceDefId, ownerType, ownerId, serviceTypeId, DURATION_MINUTES, basePrice, priceMax);
            UUID masterServiceId = UUID.randomUUID();
            jdbcTemplate.update("INSERT INTO master_services (id, master_id, service_def_id, is_active, "
                            + "created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                    masterServiceId, masterId, serviceDefId);
            return masterServiceId;
        }
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────────

    private String url(UUID masterId) {
        return "/api/v1/masters/" + masterId + "/bookings";
    }

    private ResponseEntity<String> create(UUID masterId, String token, OffsetDateTime startsAt) {
        return create(masterId, salon.masterServiceId(), token, startsAt);
    }

    /** Overload for targets other than the default salon master, whose own service id differs. */
    private ResponseEntity<String> create(
            UUID masterId, UUID masterServiceId, String token, OffsetDateTime startsAt) {
        return restTemplate.exchange(
                url(masterId), org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body(startsAt, masterServiceId), bearerHeaders(token)), String.class);
    }

    private String body(OffsetDateTime startsAt) {
        return body(startsAt, salon.masterServiceId());
    }

    private String body(OffsetDateTime startsAt, UUID masterServiceId) {
        return """
                {"masterServiceIds":["%s"],"startsAt":"%s",
                 "guest":{"name":"Марія","surname":"Левченко","phone":"%s"}}
                """.formatted(masterServiceId, startsAt, RAW_PHONE);
    }

    private String bodyWithPhone(OffsetDateTime startsAt, String phone) {
        return """
                {"masterServiceIds":["%s"],"startsAt":"%s",
                 "guest":{"name":"Марія","surname":"Левченко","phone":"%s"}}
                """.formatted(salon.masterServiceId(), startsAt, phone);
    }

    private record Invited(UUID userId, String email, UUID masterId) {
    }

    /**
     * A second ACTIVE salon for an owner who already has one — no master, no services. Enough to
     * take {@code SalonRepository#findIdsByOwnerIdAndIsActiveTrue} to two rows, which is the sole
     * condition {@code StaffBookingScopeResolver#ownedSalonId} branches on.
     *
     * <p>Deliberately master-less: {@code idx_masters_user_owner_type} (V56) allows a user at most
     * one {@code SALON_OWNER}-type master row, so a second salon for the SAME owner cannot carry a
     * second owner-operated master, and an invited {@code SALON_MASTER} would drag the
     * {@code SecurityContext}-dependent schedule-seeding branch in for no gain.
     *
     * @return the new salon's id — the one the resolver must NOT pick
     */
    private UUID seedExtraSalonFor(UUID ownerId) {
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at) "
                + "VALUES (?, ?, ?, true, NOW(), NOW())", salonId, ownerId, "Salon-" + salonId);
        return salonId;
    }

    /** An invited, read-only {@code SALON_MASTER} of the given salon — no schedule needed. */
    private Invited seedInvitedMaster(Salon salon) {
        SeededUser user = insertUser("SALON_MASTER", salon.salonId());
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, "
                + "updated_at) VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                masterId, user.id(), salon.salonId());
        return new Invited(user.id(), user.email(), masterId);
    }
}
