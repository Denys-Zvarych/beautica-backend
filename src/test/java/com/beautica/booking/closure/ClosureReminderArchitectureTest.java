package com.beautica.booking.closure;

import com.beautica.booking.entity.Booking;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.booking.service.BookingService;
import com.beautica.notification.service.NotificationOutboxService;
import com.tngtech.archunit.core.domain.AccessTarget;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMember;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Modifying;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 29.5 — THE load-bearing architectural guard for the whole closure-reminder track (29.5
 * through 29.7). The entire reason Option A (a status-mutating sweeper) was rejected in favour of
 * this reminder-only design is that a scheduled process must never write {@code bookings.status}.
 * That guarantee is worth a mechanical test, not a code-review convention — see the phase-223 doc.
 *
 * <p><b>Scope.</b> Every method/constructor declared in a class residing in {@code
 * com.beautica.booking.closure..} (the Phase 29.6 job package), PLUS {@link
 * NotificationOutboxService#enqueueClosureReminder(java.util.UUID)} (the Phase 29.5 enqueue seam,
 * which lives outside that package). Neither, nor anything either TRANSITIVELY calls within this
 * codebase, may reach:
 *
 * <ul>
 *   <li>{@link Booking#setStatus(com.beautica.booking.enums.BookingStatus)},
 *   <li>any {@code @Modifying} method on {@link BookingRepository},
 *   <li>{@link EntityManager#merge(Object)} / {@link EntityManager#persist(Object)},
 *   <li>{@code NotificationOutboxService#enqueueReviewRequested} (the mass-mail vector), or
 *   <li>{@code BookingService#computeProviderCanReviewClient} (the review-window vector).
 * </ul>
 *
 * <p><b>Why a hand-rolled BFS instead of ArchUnit's fluent {@code should().callMethod(...)}
 * API.</b> The fluent API only asserts DIRECT bytecode calls. A grep-based (or direct-call-only)
 * substitute would pass the moment the forbidden call moves one indirection away — e.g. the job
 * calls a small helper that itself calls {@code setStatus}. This test instead walks the REAL call
 * graph: for every root code unit, it does a breadth-first search over {@link
 * JavaCodeUnit#getCallsFromSelf()}, following every call ArchUnit can resolve to a concrete method
 * or constructor declared somewhere under {@code com.beautica} (only that package is imported, so
 * the search is naturally bounded — a call into the JDK/Spring/etc. has no resolvable target and
 * the walk simply stops there without needing an explicit boundary check), until either a
 * forbidden target is reached (FAIL, with the full call chain in the assertion message) or the
 * graph is exhausted (PASS).
 *
 * <p><b>Anti-vacuity.</b> {@link #should_haveNonEmptyRootSet_when_bothPhase295And296HaveShipped()}
 * guards against a silently-empty root set (e.g. if the closure package were ever renamed) making
 * the main assertion pass for the wrong reason. Beyond that, this rule was sanity-checked RED by
 * temporarily adding a {@code booking.setStatus(BookingStatus.COMPLETED)} call to {@code
 * ClosureReminderClaimService.claimAndEnqueue} and re-running this test — see the phase-223 doc's
 * Status block for the recorded failure output — then reverted before this change was committed.
 */
@DisplayName("ClosureReminderArchitectureTest — the reminder path never writes bookings.status")
class ClosureReminderArchitectureTest {

    private static final String BASE_PACKAGE = "com.beautica";
    private static final String CLOSURE_PACKAGE_PREFIX = "com.beautica.booking.closure";

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE_PACKAGE);
    }

    @Test
    @DisplayName("sanity: the scanned root set is non-empty — an empty scope would make the main "
            + "assertion pass vacuously")
    void should_haveNonEmptyRootSet_when_bothPhase295And296HaveShipped() {
        assertThat(rootCodeUnits()).isNotEmpty();
    }

    @Test
    @DisplayName("no root in com.beautica.booking.closure.. or "
            + "NotificationOutboxService#enqueueClosureReminder transitively calls Booking#setStatus, "
            + "a @Modifying BookingRepository method, EntityManager#merge/#persist, "
            + "enqueueReviewRequested, or computeProviderCanReviewClient")
    void should_notReachForbiddenTarget_when_walkingClosureReminderCallGraph() {
        Set<String> modifyingBookingRepoMethods = modifyingMethodFullNames();
        List<String> violations = new ArrayList<>();

        for (JavaCodeUnit root : rootCodeUnits()) {
            findForbiddenPath(root, modifyingBookingRepoMethods)
                    .ifPresent(chain -> violations.add(String.join("  ->  ", chain)));
        }

        assertThat(violations)
                .as("the closure-reminder path must hold no reference, direct or transitive, to a "
                        + "status setter, a @Modifying BookingRepository method, "
                        + "EntityManager#merge/#persist, enqueueReviewRequested, or "
                        + "computeProviderCanReviewClient — violating call chain(s) shown above")
                .isEmpty();
    }

    private Set<JavaCodeUnit> rootCodeUnits() {
        Set<JavaCodeUnit> roots = new LinkedHashSet<>();

        JavaClass outboxServiceClass = classes.get(NotificationOutboxService.class);
        outboxServiceClass.getMethods().stream()
                .filter(m -> m.getName().equals("enqueueClosureReminder"))
                .forEach(roots::add);

        classes.stream()
                .filter(c -> c.getPackageName().equals(CLOSURE_PACKAGE_PREFIX)
                        || c.getPackageName().startsWith(CLOSURE_PACKAGE_PREFIX + "."))
                .forEach(c -> {
                    roots.addAll(c.getMethods());
                    roots.addAll(c.getConstructors());
                });

        return roots;
    }

    private Set<String> modifyingMethodFullNames() {
        return classes.get(BookingRepository.class).getMethods().stream()
                .filter(m -> m.isAnnotatedWith(Modifying.class))
                .map(JavaMember::getFullName)
                .collect(Collectors.toSet());
    }

    /**
     * Breadth-first search over the call graph reachable from {@code root}, restricted to targets
     * ArchUnit can resolve within the imported {@code com.beautica} classes (see class javadoc for
     * why that alone is a sufficient boundary). Returns the full chain of full-qualified names
     * (root first, forbidden target last) the moment a forbidden target is reached.
     */
    private Optional<List<String>> findForbiddenPath(JavaCodeUnit root, Set<String> modifyingBookingRepoMethods) {
        Deque<JavaCodeUnit> queue = new ArrayDeque<>();
        Set<JavaCodeUnit> visited = new HashSet<>();
        Map<JavaCodeUnit, JavaCodeUnit> cameFrom = new HashMap<>();
        queue.add(root);
        visited.add(root);

        while (!queue.isEmpty()) {
            JavaCodeUnit current = queue.poll();
            for (JavaCall<?> call : current.getCallsFromSelf()) {
                AccessTarget target = call.getTarget();
                if (isForbidden(target, modifyingBookingRepoMethods)) {
                    List<String> chain = reconstructChain(cameFrom, current);
                    chain.add(target.getFullName());
                    return Optional.of(chain);
                }
                Optional<? extends JavaMember> resolved = target.resolveMember();
                if (resolved.isPresent() && resolved.get() instanceof JavaCodeUnit next && visited.add(next)) {
                    cameFrom.put(next, current);
                    queue.add(next);
                }
            }
        }
        return Optional.empty();
    }

    private static List<String> reconstructChain(Map<JavaCodeUnit, JavaCodeUnit> cameFrom, JavaCodeUnit leaf) {
        Deque<String> stack = new ArrayDeque<>();
        JavaCodeUnit node = leaf;
        while (node != null) {
            stack.push(node.getFullName());
            node = cameFrom.get(node);
        }
        return new ArrayList<>(stack);
    }

    private static boolean isForbidden(AccessTarget target, Set<String> modifyingBookingRepoMethods) {
        JavaClass owner = target.getOwner();
        String name = target.getName();

        if (owner.isEquivalentTo(Booking.class) && name.equals("setStatus")) {
            return true;
        }
        if (owner.isEquivalentTo(NotificationOutboxService.class) && name.equals("enqueueReviewRequested")) {
            return true;
        }
        if (owner.isEquivalentTo(BookingService.class) && name.equals("computeProviderCanReviewClient")) {
            return true;
        }
        if (owner.isEquivalentTo(EntityManager.class) && (name.equals("merge") || name.equals("persist"))) {
            return true;
        }
        return modifyingBookingRepoMethods.contains(target.getFullName());
    }
}
