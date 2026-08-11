package com.beautica.booking;

import static org.mockito.Mockito.mockingDetails;

import java.sql.Connection;
import java.sql.PreparedStatement;
import javax.sql.DataSource;
import org.mockito.stubbing.Answer;

/**
 * Shared machinery for the two create-path <b>atomic rollback</b> tests —
 * {@code AppointmentCreateIT#should_return409AndPersistNothing_when_chainOverlapsExistingBooking} and
 * {@code GuestVisitLinkParityIT#should_rollbackWholeVisit_when_chainOverlapsExistingBooking}.
 *
 * <p><b>Why those tests need machinery at all.</b> Both used to pre-seed a CONFIRMED blocker and expect
 * the create path's span {@code existsOverlap} to reject the chain. Since the schedule-fit gate landed
 * (2026-08-11) that arrangement is dead: for COMMITTED data the gate strictly DOMINATES
 * {@code existsOverlap}. The slot oracle subtracts exactly the {@code status = 'CONFIRMED'} rows
 * {@code existsOverlap} scans (over an overlap-, not containment-, scoped day window) and sizes its
 * candidate block to Σ of the chain's effective durations, a superset of {@code [firstStart, lastEnd)}.
 * So {@code EXCLUDE fires ⟹ existsOverlap fires ⟹ the gate already rejected} — a pre-seeded blocker can
 * never reach the INSERT, and "nothing persisted" becomes vacuously true.
 *
 * <p><b>What these helpers enable instead.</b> The real-world case the GIST
 * {@code no_overlapping_bookings} backstop exists for: a competing booking committing AFTER both
 * pre-checks passed. The blocker is committed from inside the {@code existsOverlap} answer — so at gate
 * time and at check time the master is genuinely free, nothing is stubbed — and the subsequent
 * {@code flush()} trips the constraint, rolling the whole visit back.
 */
final class OverlapRaceSupport {

    private OverlapRaceSupport() {
    }

    /**
     * The answer that forwards an invocation to the genuine bean behind a {@code @MockitoSpyBean}.
     *
     * <p>Needed because neither usual route works for a Spring Data repository:
     * {@code invocation.callRealMethod()} throws {@code MockitoException} (the bean is a JDK proxy, so the
     * spy has no real method to call), and the bean-override spy exposes no {@code spiedInstance}. What it
     * does carry is a forwarding default answer pointing at the real repository — invoking that runs the
     * real query against live DB state, which is what keeps these tests honest: the guard under test is
     * never stubbed, only an interleaving is injected around it.
     *
     * @param spy a bean replaced by {@code @MockitoSpyBean}
     * @return its forwarding default answer; never {@code null} for a spy
     */
    static Answer<?> forwardingAnswerOf(Object spy) {
        Answer<?> forwarding = mockingDetails(spy).getMockCreationSettings().getDefaultAnswer();
        if (forwarding == null) {
            throw new IllegalStateException(
                    "Expected a @MockitoSpyBean carrying a forwarding default answer, got: " + spy);
        }
        return forwarding;
    }

    /**
     * Executes {@code sql} on a connection taken DIRECTLY from the pool, in its own auto-committed
     * transaction.
     *
     * <p>Deliberately not {@code JdbcTemplate}: called on the request thread it would be bound by
     * {@code DataSourceUtils} to the very transaction under test, so the row would roll back with it and
     * the blocker would vanish — taking the "only the blocker survives" assertion with it. Here the row is
     * committed the moment this returns, hence visible to the pending flush and to the post-request
     * assertions.
     */
    static void commitOutsideTransaction(DataSource dataSource, String sql, Object... args)
            throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            statement.executeUpdate();
        }
    }
}
