package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.common.ApiResponse;
import com.beautica.common.TimeZones;
import com.beautica.master.dto.WeeklyScheduleDayRequest;
import com.beautica.master.dto.WeeklyScheduleRequest;
import com.beautica.master.dto.WorkIntervalDto;
import com.beautica.master.service.MasterScheduleService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.impl.DefaultHttpRequestRetryStrategy;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.TimeValue;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared rig for the {@code POST /api/v1/masters/&#123;masterId&#125;/bookings} integration suites —
 * {@link StaffBookingEndpointIT} (the endpoint's authorization matrix) and
 * {@link AbstractWalkInBookingSmsIT}'s two wire-level SMS classes.
 *
 * <h2>Why it exists</h2>
 * {@code seedSalon}, {@code giveWorkingHours}, {@code tokenFor} and {@code bearerHeaders} were
 * near-verbatim copies in both places (QA LOW, left open in the Phase 22.7 pass because extracting
 * them forces a re-run of {@code StaffBookingEndpointIT}; that re-run is now in scope). They were
 * not merely similar — they were the SAME fixture: same 09:00–17:00 seven-day schedule, same
 * owner-operated {@code SALON_OWNER}-type master, same «Манікюр» / 60 min / 350.00 service, same
 * login round trip. Two copies of a fixture drift silently, and a drifted fixture makes two suites
 * disagree about what "a bookable master" is while both stay green.
 *
 * <h2>Why an abstract base and not a static helper class</h2>
 * Every helper here needs one or more Spring-injected collaborators ({@code TestRestTemplate},
 * {@code JdbcTemplate}, {@code PasswordEncoder}, {@code MasterScheduleService}). A static utility
 * would have to take all four as parameters at every call site, and both consumers already extend
 * {@link AbstractIntegrationTest}, so one intermediate class costs nothing and keeps the call sites
 * unchanged.
 *
 * <h2>The HTTP factory is static and never closed per class</h2>
 * ONE Apache HC5 pool for the whole JVM, released by a shutdown hook. A {@code @AfterAll} here would
 * destroy the pool after whichever subclass finished first and leave the others — which share cached
 * Spring contexts — with a closed pool. It is re-installed in {@link #installHttpFactory()} on every
 * test because {@link AbstractIntegrationTest#cleanDb()} replaces the {@code TestRestTemplate}'s
 * factory after each one. Zero retries and finite 10 s timeouts are deliberate and must be kept: a
 * rate-limit 429 that resets the socket has to fail fast rather than hang the suite for the full
 * {@code Retry-After} window — and these routes ARE rate-limited (see
 * {@code BookingRateLimitFilter}). {@code SimpleClientHttpRequestFactory} cannot be used: it rejects
 * PATCH.
 *
 * <p>Fixture data uses no occupied-territory locality references.
 */
abstract class AbstractStaffBookingIT extends AbstractIntegrationTest {

    protected static final String TEST_PASSWORD = "Str0ngP@ss1!";
    protected static final int DURATION_MINUTES = 60;
    protected static final BigDecimal PRICE = new BigDecimal("350.00");

    /** The staff-typed number; the service normalises it before the insert and before the send. */
    protected static final String RAW_PHONE = "050 123 45 67";
    protected static final String E164_PHONE = "+380501234567";

    /** The PROVIDER's custom {@code service_definitions.name} — free text, shown in API responses. */
    protected static final String SERVICE_NAME = "Манікюр";

    /**
     * The PLATFORM {@code service_types.name_uk} of the type {@link #insertService} last attached —
     * the string the walk-in confirmation SMS renders (security LOW, 2026-08-19: the provider's own
     * {@link #SERVICE_NAME} must never reach a branded message to a number that never opted in).
     *
     * <p>Not a constant: {@code resolveUnusedServiceTypeId} deliberately picks whichever seeded type
     * is still free for this owner, so which platform name applies is decided at fixture time.
     */
    protected String lastPlatformServiceName;
    protected static final String MASTER_FIRST_NAME = "Марія";
    protected static final String MASTER_LAST_NAME = "Левченко";
    /** «Ім'я Прізвище» exactly as {@code StaffBookingService#masterName} renders it into the SMS. */
    protected static final String MASTER_NAME = MASTER_FIRST_NAME + " " + MASTER_LAST_NAME;

    private static final HttpComponentsClientHttpRequestFactory HTTP_FACTORY = createHttpFactory();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                HTTP_FACTORY.destroy();
            } catch (Exception ignored) {
                // Best-effort pool release on JVM exit; throwing from a shutdown hook only obscures
                // the real exit status.
            }
        }));
    }

    private static HttpComponentsClientHttpRequestFactory createHttpFactory() {
        var client = HttpClients.custom()
                .setRetryStrategy(new DefaultHttpRequestRetryStrategy(0, TimeValue.ZERO_MILLISECONDS))
                .build();
        var factory = new HttpComponentsClientHttpRequestFactory(client);
        factory.setConnectionRequestTimeout(10_000);
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(10_000);
        return factory;
    }

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private MasterScheduleService masterScheduleService;

    /** The default fixture: one salon, its owner-operated master and that master's one service. */
    protected Salon salon;

    /**
     * Runs before any subclass {@code @BeforeEach} (JUnit orders superclass callbacks first), so a
     * subclass may assume both the factory and {@link #salon} are ready.
     */
    @BeforeEach
    void installHttpFactoryAndSeedSalon() {
        installHttpFactory();
        salon = seedSalon();
    }

    protected void installHttpFactory() {
        restTemplate.getRestTemplate().setRequestFactory(HTTP_FACTORY);
    }

    /** Distinct from {@link #MASTER_FIRST_NAME}/{@link #MASTER_LAST_NAME} — an attribution bug
     * cannot fail an assertion if guest and master share a name. */
    protected static final String GUEST_FIRST_NAME = "Оксана";
    protected static final String GUEST_LAST_NAME = "Гончар";

    // ── fixture records ───────────────────────────────────────────────────────────

    protected record Salon(UUID salonId, UUID ownerId, String ownerEmail, UUID masterId,
                           UUID masterServiceId, String bookingSlug) {
    }

    /** One walk-in visit of N chained services, however the concrete shape actually persists it. */
    protected record Visit(UUID appointmentId, List<UUID> bookingIds) {
        UUID booking(int i) {
            return bookingIds.get(i);
        }
    }

    protected record SeededUser(UUID id, String email) {
    }

    /**
     * A solo master and everything needed to authenticate as them and book against them.
     *
     * <p>Promoted here (with {@link #seedIndependentMaster()}) when {@link StaffBookingReadPathIT}
     * became the second suite in this hierarchy to need a salon-less master — the Q4
     * two-occurrence threshold. It was private to {@link StaffBookingEndpointIT}; privacy is not a
     * licence to copy, and a second hand-written copy of a fixture is exactly how the two suites
     * end up disagreeing about what "an independent master" is while both stay green.
     */
    protected record Independent(UUID userId, String email, UUID masterId, UUID masterServiceId) {
    }

    // ── seeding ───────────────────────────────────────────────────────────────────

    /**
     * An OWNER-OPERATED salon master ({@code master_type = 'SALON_OWNER'}), deliberately: seeding the
     * weekly schedule goes through {@code enforceCanManageMasterSchedule}, whose
     * invited-{@code SALON_MASTER} branch reads the actor's role from the {@code SecurityContext} —
     * absent when a fixture calls the service directly. Nothing under test cares which type it is.
     *
     * <p>A {@code booking_slug} is seeded too, so the same master can also be reached through the
     * public guest (LINK) booking route. It is derived from the master id, which keeps it unique
     * against V86's partial unique index and inside the
     * {@code ^[a-z0-9][a-z0-9\-]*[a-z0-9]$} / 60-char contract without any extra state.
     */
    protected Salon seedSalon() {
        SeededUser owner = insertUser("SALON_OWNER", null);

        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at) "
                + "VALUES (?, ?, ?, true, NOW(), NOW())", salonId, owner.id(), "Salon-" + salonId);

        UUID masterId = UUID.randomUUID();
        String slug = "m-" + masterId;
        jdbcTemplate.update("INSERT INTO masters (id, user_id, salon_id, master_type, booking_slug, "
                        + "is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_OWNER', ?, true, NOW(), NOW())",
                masterId, owner.id(), salonId, slug);

        UUID masterServiceId = insertService(masterId, "SALON", salonId);
        giveWorkingHours(owner.id(), masterId);
        return new Salon(salonId, owner.id(), owner.email(), masterId, masterServiceId, slug);
    }

    protected SeededUser insertUser(String role, UUID salonId) {
        UUID id = UUID.randomUUID();
        String email = "staff-fixture-" + id + "@beautica.test";
        jdbcTemplate.update("INSERT INTO users (id, email, password_hash, role, salon_id, first_name, "
                        + "last_name, is_active, email_verified) VALUES (?, ?, ?, ?, ?, ?, ?, true, true)",
                id, email, passwordEncoder.encode(TEST_PASSWORD), role, salonId,
                MASTER_FIRST_NAME, MASTER_LAST_NAME);
        return new SeededUser(id, email);
    }

    protected UUID insertService(UUID masterId, String ownerType, UUID ownerId) {
        UUID serviceDefId = UUID.randomUUID();
        UUID serviceTypeId = resolveUnusedServiceTypeId(ownerType, ownerId);
        lastPlatformServiceName = jdbcTemplate.queryForObject(
                "SELECT name_uk FROM service_types WHERE id = ?", String.class, serviceTypeId);
        jdbcTemplate.update("INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, "
                        + "updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, 0, true, NOW(), NOW())",
                serviceDefId, ownerType, ownerId, SERVICE_NAME,
                serviceTypeId, DURATION_MINUTES, PRICE);
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, "
                + "updated_at) VALUES (?, ?, ?, true, NOW(), NOW())", masterServiceId, masterId, serviceDefId);
        return masterServiceId;
    }

    /**
     * A salon-less {@code INDEPENDENT_MASTER} with one service and the same seven-day 09:00–17:00
     * schedule {@link #seedSalon()} gives its master, so "tomorrow at noon" is bookable against
     * either. See {@link Independent} for why this lives on the base class.
     */
    protected Independent seedIndependentMaster() {
        SeededUser user = insertUser("INDEPENDENT_MASTER", null);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO masters (id, user_id, master_type, is_active, created_at, updated_at) "
                + "VALUES (?, ?, 'INDEPENDENT_MASTER', true, NOW(), NOW())", masterId, user.id());
        UUID masterServiceId = insertService(masterId, "INDEPENDENT_MASTER", user.id());
        giveWorkingHours(user.id(), masterId);
        return new Independent(user.id(), user.email(), masterId, masterServiceId);
    }

    /** 09:00–17:00 on every ISO weekday, so "tomorrow" is a working day whatever day it is today. */
    protected void giveWorkingHours(UUID masterUserId, UUID masterId) {
        List<WeeklyScheduleDayRequest> days = Arrays.stream(new int[]{1, 2, 3, 4, 5, 6, 7})
                .mapToObj(dow -> new WeeklyScheduleDayRequest(dow,
                        List.of(new WorkIntervalDto(LocalTime.of(9, 0), LocalTime.of(17, 0)))))
                .toList();
        masterScheduleService.upsertWeeklySchedule(masterUserId, masterId, null,
                new WeeklyScheduleRequest(LocalDate.now(TimeZones.KYIV), null, days));
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────────

    protected HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    protected String tokenFor(String email) {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(resp.getStatusCode()).as("login must succeed for %s", email).isEqualTo(HttpStatus.OK);
        try {
            return objectMapper.readValue(resp.getBody(),
                    new TypeReference<ApiResponse<AuthResponse>>() {}).data().accessToken();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse login response for " + email, e);
        }
    }

    /**
     * Creates a walk-in VISIT through the real {@code POST /masters/&#123;masterId&#125;/bookings}
     * endpoint — {@code Appointment} header + N chained {@code bookings} rows, even at N = 1
     * (Phase 258 D2, no size short-circuit).
     *
     * <p>Promoted here from {@code VisitStaffBookingShapeIT} when {@link StaffVisitItemRescheduleIT}
     * became the second suite in this hierarchy to need a real walk-in visit (Q4 two-occurrence
     * threshold). A second hand-written copy is exactly how two suites end up disagreeing about what
     * "a walk-in visit" is while both stay green.
     */
    protected Visit postWalkIn(UUID masterId, List<UUID> masterServiceIds, String token, OffsetDateTime startsAt) {
        String idsJson = masterServiceIds.stream()
                .map(id -> "\"" + id + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        String body = """
                {"masterServiceIds":[%s],"startsAt":"%s",
                 "guest":{"name":"%s","surname":"%s","phone":"%s"}}
                """.formatted(idsJson, startsAt, GUEST_FIRST_NAME, GUEST_LAST_NAME, RAW_PHONE);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/masters/" + masterId + "/bookings", HttpMethod.POST,
                new HttpEntity<>(body, bearerHeaders(token)), String.class);
        assertThat(resp.getStatusCode())
                .as("walk-in visit setup must succeed — body=%s", resp.getBody())
                .isEqualTo(HttpStatus.CREATED);
        try {
            var data = objectMapper.readTree(resp.getBody()).path("data");
            UUID appointmentId = UUID.fromString(data.path("id").asText());
            List<UUID> bookingIds = new java.util.ArrayList<>();
            data.path("items").forEach(item -> bookingIds.add(UUID.fromString(item.path("bookingId").asText())));
            return new Visit(appointmentId, bookingIds);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse visit response: " + resp.getBody(), e);
        }
    }

    // ── time ──────────────────────────────────────────────────────────────────────

    /**
     * Tomorrow 12:00 Kyiv: inside the seeded 09:00–17:00 window, aligned to the 30-minute slot grid
     * (a locked product decision, 2026-08-11), and unambiguously in the future whenever the suite
     * runs. No clock is pinned in this hierarchy, so every time read is live — coherent, not mixed.
     * A frozen clock would break token minting: {@code JwtTokenProvider} mints expiry off the
     * injected {@code Clock} while JJWT validates against its own system clock.
     */
    protected OffsetDateTime tomorrowAtNoon() {
        return tomorrowAt(LocalTime.NOON);
    }

    protected OffsetDateTime tomorrowAt(LocalTime time) {
        LocalDate tomorrow = LocalDate.now(TimeZones.KYIV).plusDays(1);
        return ZonedDateTime.of(tomorrow, time, TimeZones.KYIV).toOffsetDateTime();
    }

    // ── DB assertions ─────────────────────────────────────────────────────────────

    protected int bookingCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM bookings", Integer.class);
    }

    protected Map<String, Object> onlyBooking() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM bookings");
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }
}
