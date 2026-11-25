package com.beautica.common.exception;

import com.beautica.common.exception.ExceptionMessageInputScanner.HelperCalls;
import com.beautica.common.exception.ExceptionMessageInputScanner.Site;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Phase 500 audit (security LOW) — a {@code BusinessException}/{@code ConflictException} message is logged at
 * INFO/WARN by {@code GlobalExceptionHandler#handleBusiness}, so it must not interpolate caller input. Every
 * {@code new [qualified.]BusinessException(...)} / {@code ConflictException(...)}, {@code ::new} reference, and
 * {@code super(...)} in a direct subclass in {@code src/main/java} whose message references a non-constant value fails
 * this test unless built with {@link BusinessException#withUserInput} or listed in {@link #REVIEWED_SITES}.
 *
 * <p>Each entry pins file + exact (whitespace-collapsed) message + the NUMBER of sites, so a copy-pasted duplicate or a
 * removed site fails. An entry whose message is a helper PARAMETER passed straight through is only safe if every
 * caller passes fixed text, so {@link #PASS_THROUGH_HELPERS} scans those callers too.
 */
@DisplayName("BusinessException messages — no caller input at INFO/WARN (phase 500)")
class BusinessExceptionMessageGuardTest {

    private static final Path MAIN_SOURCES = Path.of("src/main/java");
    private static final String PASS_THROUGH = "param pass-through; callers checked in PASS_THROUGH_HELPERS";

    record Reviewed(String file, String message, int sites, String reason) {

        boolean covers(Site site) {
            return site.file().equals(file) && site.message().equals(message);
        }
    }

    /** Helpers whose message parameter is passed straight to a reviewed site; every caller must pass fixed text. */
    private record PassThroughHelper(String declaringFile, String callerScope, Set<String> helpers, String param) {
    }

    private static final List<Reviewed> REVIEWED_SITES = List.of(
            new Reviewed("com/beautica/booking/dto/StaffBookingCommand.java",
                    "field + \" is required\"", 1, PASS_THROUGH),
            new Reviewed("com/beautica/booking/dto/StaffBookingScope.java",
                    "field + \" is required\"", 1, PASS_THROUGH),
            new Reviewed("com/beautica/booking/dto/StaffClientRef.java",
                    "field + \" must not exceed \" + MAX_NAME_LENGTH + \" characters\"", 1, PASS_THROUGH),
            new Reviewed("com/beautica/booking/dto/StaffClientRef.java",
                    "field + \" must not contain control characters\"", 1, PASS_THROUGH),
            new Reviewed("com/beautica/booking/dto/StaffClientRef.java",
                    "field + \" is required\"", 1, PASS_THROUGH),
            new Reviewed("com/beautica/booking/entity/Booking.java",
                    "field + \" must not be blank for a STAFF walk-in booking\"", 1, PASS_THROUGH),
            new Reviewed("com/beautica/booking/service/PostLockRescheduleGuard.java",
                    "staleMessage", 1, PASS_THROUGH),
            new Reviewed("com/beautica/common/exception/InviteTokenException.java",
                    "message", 1, PASS_THROUGH),
            new Reviewed("com/beautica/common/exception/ConflictException.java",
                    "message", 1, "super pass-through; every new ConflictException(...) is a scanned throw site"),
            new Reviewed("com/beautica/common/exception/IdentityException.java",
                    "code.clientMessage()", 1, "enum-constant client messages"),
            new Reviewed("com/beautica/common/exception/OwnerAccountDeletionBlockedException.java",
                    "\"Owner account deletion blocked: \" + code.name()", 1, "enum name"),
            new Reviewed("com/beautica/booking/service/AppointmentTransitionService.java",
                    "\"Cannot transition service from %s to %s\".formatted(item.getStatus(), target)", 1,
                    "enum statuses"),
            new Reviewed("com/beautica/booking/service/AppointmentTransitionService.java",
                    "\"Cannot reschedule service in status %s\".formatted(target.getStatus())", 1, "enum status"),
            new Reviewed("com/beautica/booking/service/BookingService.java",
                    "\"Cannot cancel a booking in status %s\".formatted(current)", 1, "enum status"),
            new Reviewed("com/beautica/booking/service/BookingService.java",
                    "\"Cannot reschedule a booking in status %s\".formatted(current)", 2, "enum status"),
            new Reviewed("com/beautica/booking/service/BookingService.java",
                    "\"Cannot transition from %s to %s\".formatted(booking.getStatus(), target)", 1, "enum statuses"),
            new Reviewed("com/beautica/booking/service/GuestCancelSelection.java",
                    "\"Скасування недоступне — менше ніж %d год до запису\".formatted(cancelWindowHours)", 1,
                    "config int"),
            new Reviewed("com/beautica/booking/service/ScheduleOverrideConflictService.java",
                    "(\"This override would decline %d bookings, exceeding the %d-per-write \" + "
                            + "\"abuse-prevention limit\") .formatted(conflicts.size(), MAX_CONFLICTS_PER_WRITE)",
                    1, "count + constant"),
            new Reviewed("com/beautica/booking/service/ScheduleOverrideConflictService.java",
                    "(\"This override would leave %d confirmed booking(s) without availability; \" + "
                            + "\"resubmit with cancelOverlapping=true to confirm cancellation\") "
                            + ".formatted(conflicts.size())",
                    1, "count"),
            new Reviewed("com/beautica/master/service/MasterScheduleService.java",
                    "\"Schedule window overlaps an existing window starting \" + existing.getValidFrom()", 1,
                    "server-stored date"),
            new Reviewed("com/beautica/master/service/ScheduleDateMath.java",
                    "\"Date range exceeds the maximum of \" + (maxSpanDays + 1) + \" days\"", 1, "server cap"),
            new Reviewed("com/beautica/salon/service/SalonService.java",
                    "master.getMasterType() == MasterType.SALON_OWNER ? MasterService.OWNER_MASTER_NOT_REMOVABLE "
                            + ": \"Only an invited SALON_MASTER may be removed here\"",
                    1, "ternary over two constants"),
            new Reviewed("com/beautica/service/service/ServiceCatalogService.java",
                    "\"Master has \" + futureConfirmedCount + \" future confirmed booking(s) for this \" + "
                            + "\"service; cancel or decline them first\"",
                    1, "count"),
            new Reviewed("com/beautica/user/ClientAccountDeletionService.java",
                    "(\"Забагато активних записів (%d) для видалення акаунту. Спочатку скасуйте частину \" + "
                            + "\"майбутніх записів (максимум %d) і спробуйте ще раз.\") "
                            + ".formatted(futureCandidates.size(), MAX_FUTURE_BOOKINGS_PER_SELF_DELETE)",
                    1, "count + constant"),
            new Reviewed("com/beautica/user/StaffAccountSelfDeletionService.java",
                    "message", 1, "local built in-method from counts + constant"));

    private static final List<PassThroughHelper> PASS_THROUGH_HELPERS = List.of(
            new PassThroughHelper("com/beautica/booking/dto/StaffBookingCommand.java",
                    "com/beautica/booking/dto/StaffBookingCommand.java", Set.of("required"), "field"),
            new PassThroughHelper("com/beautica/booking/dto/StaffBookingScope.java",
                    "com/beautica/booking/dto/StaffBookingScope.java", Set.of("required"), "field"),
            new PassThroughHelper("com/beautica/booking/dto/StaffClientRef.java",
                    "com/beautica/booking/dto/StaffClientRef.java", Set.of("requireText", "boundedText"), "field"),
            new PassThroughHelper("com/beautica/booking/entity/Booking.java",
                    "com/beautica/booking/entity/Booking.java", Set.of("requireStaffText"), "field"),
            new PassThroughHelper("com/beautica/booking/service/PostLockRescheduleGuard.java",
                    "com/beautica/booking/service/",
                    Set.of("assertBookingStillConfirmedAndFree", "assertVisitItemsStillConfirmedAndFree", "evaluate"),
                    "staleMessage"),
            new PassThroughHelper("com/beautica/common/exception/InviteTokenException.java",
                    "com/beautica/", Set.of("InviteTokenException"), "message"));

    @Test
    @DisplayName("every interpolated business message is withUserInput or reviewed, with the pinned site count")
    void should_haveNoAllowListViolation_when_scanningMainSources() {
        // Arrange
        List<Site> flagged = mainSources().stream()
                .flatMap(file -> ExceptionMessageInputScanner.scan(file.relative(), file.source()).stream())
                .toList();

        // Act
        List<String> violations = allowListViolations(flagged, REVIEWED_SITES);

        // Assert
        assertThat(violations)
                .as("Interpolated message reaches INFO/WARN logs: use BusinessException.withUserInput, or add/adjust a "
                        + "REVIEWED_SITES entry proving the value is server-side only")
                .isEmpty();
    }

    @Test
    @DisplayName("every caller of a pass-through helper passes fixed text")
    void should_passOnlyConstants_when_callingPassThroughHelpers() {
        // Arrange
        List<SourceFile> sources = mainSources();
        List<Site> flagged = new ArrayList<>();
        List<String> withoutCallers = new ArrayList<>();

        // Act
        for (PassThroughHelper helper : PASS_THROUGH_HELPERS) {
            int calls = 0;
            for (SourceFile file : sources) {
                if (file.relative().startsWith(helper.callerScope())) {
                    HelperCalls result = ExceptionMessageInputScanner.scanHelperCalls(
                            file.relative(), file.source(), helper.helpers(), helper.param());
                    calls += result.calls();
                    flagged.addAll(result.flagged());
                }
            }
            if (calls == 0) {
                withoutCallers.add(helper.declaringFile() + " " + helper.helpers());
            }
        }

        // Assert
        assertThat(flagged).as("a pass-through helper was called with a non-constant message argument").isEmpty();
        assertThat(withoutCallers).as("stale PASS_THROUGH_HELPERS entry (no calls found)").isEmpty();
    }

    @Test
    @DisplayName("a planted concatenated caller value is flagged")
    void should_flagSite_when_messageConcatenatesVariable() {
        // Arrange
        String planted = "class P { void f(String name) {\n"
                + "    throw new BusinessException(HttpStatus.BAD_REQUEST, \"x: \" + name);\n} }";

        // Act
        List<Site> flagged = ExceptionMessageInputScanner.scan("P.java", planted);

        // Assert
        assertThat(flagged).containsExactly(new Site("P.java", 2, "\"x: \" + name"));
    }

    @Test
    @DisplayName("format / formatted / bare-parameter / qualified / ::new sites are flagged")
    void should_flagSite_when_messageFormatsOrPassesVariable() {
        // Arrange
        String planted = """
                class P { void f(String name) {
                    throw new ConflictException(String.format("dup %s", name));
                    throw new BusinessException(HttpStatus.CONFLICT, "dup %s".formatted(name));
                    throw new BusinessException(name);
                    throw new com.beautica.common.exception.BusinessException(HttpStatus.CONFLICT, "x" + name);
                    Optional.of(name).map(ConflictException::new).orElseThrow();
                } }""";

        // Act
        List<Site> flagged = ExceptionMessageInputScanner.scan("P.java", planted);

        // Assert
        assertThat(flagged).extracting(Site::line).containsExactly(2, 3, 4, 5, 6);
        assertThat(flagged.get(4).message()).isEqualTo(ExceptionMessageInputScanner.METHOD_REFERENCE_MESSAGE);
    }

    @Test
    @DisplayName("a subclass super(...) message is scanned; super in an unrelated class is not")
    void should_flagSuperMessage_when_classExtendsBusinessException() {
        // Arrange
        String subclass = """
                class LeakyException extends BusinessException {
                    LeakyException(String name) {
                        super(HttpStatus.CONFLICT, "Taken: " + name);
                    }
                    LeakyException() {
                        super(HttpStatus.CONFLICT, "Taken");
                    }
                }""";
        String unrelated = "class Other extends Base { Other(String name) { super(\"x\" + name); } }";

        // Act
        List<Site> subclassSites = ExceptionMessageInputScanner.scan("LeakyException.java", subclass);
        List<Site> unrelatedSites = ExceptionMessageInputScanner.scan("Other.java", unrelated);

        // Assert
        assertThat(subclassSites).containsExactly(new Site("LeakyException.java", 3, "\"Taken: \" + name"));
        assertThat(unrelatedSites).isEmpty();
    }

    @Test
    @DisplayName("constant messages, withUserInput and comments are not flagged")
    void should_notFlag_when_messageIsConstantOrMarked() {
        // Arrange
        String planted = """
                class P { void f(String name) {
                    // new BusinessException(HttpStatus.BAD_REQUEST, "x: " + name)
                    throw new BusinessException(HttpStatus.CONFLICT, "Slot (taken), " + MAX_LIMIT + " max");
                    throw new ConflictException(Messages.SLOT_TAKEN);
                    throw BusinessException.withUserInput(HttpStatus.BAD_REQUEST, "x: " + name);
                    String s = "new BusinessException(\\"y\\" + name)";
                } }""";

        // Act
        List<Site> flagged = ExceptionMessageInputScanner.scan("P.java", planted);

        // Assert
        assertThat(flagged).isEmpty();
    }

    @Test
    @DisplayName("a pass-through caller handing over a variable is flagged (plain, lambda, type-argument); literal and forward are not")
    void should_flagHelperCall_when_callerPassesVariable() {
        // Arrange
        String planted = """
                record R(String name, String label) {
                    R {
                        name = requireText(name, "Client name");
                        name = boundedText(name, label);
                        name = Optional.of(name).map(v -> requireText(v, label)).orElseThrow();
                        name = R.<String>requireText(name, label);
                    }
                    private static String boundedText(String value, String field) {
                        return requireText(value, field);
                    }
                    private static <T> String requireText(T value, String field) {
                        if (value == null) {
                            throw new BusinessException(HttpStatus.BAD_REQUEST, field + " is required");
                        }
                        return value.toString();
                    }
                    void elsewhere(String field) {
                        requireText(name, field);
                    }
                }""";

        // Act
        HelperCalls result = ExceptionMessageInputScanner.scanHelperCalls(
                "R.java", planted, Set.of("requireText", "boundedText"), "field");

        // Assert
        assertThat(result.calls()).isEqualTo(6);
        assertThat(result.flagged()).extracting(Site::line, Site::message)
                .containsExactly(
                        tuple(4, "label"),
                        tuple(5, "label"),
                        tuple(6, "label"),
                        tuple(18, "field"));
    }

    @Test
    @DisplayName("a planted duplicate of a reviewed message breaks the pinned site count")
    void should_reportCountMismatch_when_reviewedMessageIsDuplicated() {
        // Arrange
        String planted = """
                class P { void f(Status current) {
                    throw new BusinessException("Bad status %s".formatted(current));
                    throw new BusinessException("Bad status %s".formatted(current));
                } }""";
        List<Reviewed> reviewed = List.of(
                new Reviewed("P.java", "\"Bad status %s\".formatted(current)", 1, "enum status"));
        List<Site> flagged = ExceptionMessageInputScanner.scan("P.java", planted);

        // Act
        List<String> violations = allowListViolations(flagged, reviewed);

        // Assert
        assertThat(violations).singleElement(InstanceOfAssertFactories.STRING).startsWith("COUNT").contains("expected 1, found 2");
    }

    /** Unreviewed sites plus every entry whose live site count differs from its pinned {@code sites}. */
    static List<String> allowListViolations(List<Site> flagged, List<Reviewed> reviewed) {
        List<String> violations = new ArrayList<>();
        flagged.stream()
                .filter(site -> reviewed.stream().noneMatch(entry -> entry.covers(site)))
                .forEach(site -> violations.add("UNREVIEWED " + site.file() + ":" + site.line() + " " + site.message()));
        for (Reviewed entry : reviewed) {
            long found = flagged.stream().filter(entry::covers).count();
            if (found != entry.sites()) {
                violations.add("COUNT " + entry.file() + " " + entry.message()
                        + ": expected " + entry.sites() + ", found " + found);
            }
        }
        return violations;
    }

    private record SourceFile(String relative, String source) {
    }

    private static List<SourceFile> mainSources() {
        assertThat(MAIN_SOURCES).as("test runs from the beautica-backend module root").isDirectory();
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            return files.filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .map(path -> new SourceFile(
                            MAIN_SOURCES.relativize(path).toString().replace('\\', '/'), read(path)))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
