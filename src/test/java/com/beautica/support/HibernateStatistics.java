package com.beautica.support;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;

/**
 * Hands a test Hibernate's {@link Statistics} with collection enabled — the statement-count probe
 * behind every "this page must stay at N statements" gate in this suite.
 *
 * <p>Extracted for the duplicated test-helper finding (backend-QA 2026-09-20, QA playbook Q4:
 * three occurrences means the
 * extraction is overdue). The three-line unwrap-and-enable body was copy-pasted VERBATIM into
 * {@code BookingSalonBookingsPartitionIT}, {@code BookingSalonBookingsIT} and
 * {@code BookingPriceRangeContractIT}, and eight further IT classes beyond those.
 *
 * <p><b>Why a static utility and not a {@code protected} method on {@code AbstractIntegrationTest}.</b>
 * That would be the obvious home, and it does not compile: eleven existing classes declare
 * {@code private Statistics statistics()}, and a {@code private} member cannot override a
 * {@code protected} one — every one of them would break at once ("attempting to assign weaker
 * access privileges"). A static utility is reachable from both the {@code @SpringBootTest} and
 * {@code @DataJpaTest} families, conflicts with no existing member, and lets the remaining classes
 * migrate one at a time instead of in a single 11-file change whose test scope would dwarf the
 * defect it fixes.
 *
 * <p><b>Enabling is not optional and not idempotent-by-luck.</b> {@code hibernate.generate_statistics}
 * is off in the {@code test} profile, so a caller that reads {@link SessionFactory#getStatistics()}
 * without enabling collection gets a live object whose every counter is frozen at zero — and a
 * statement-count assertion against zero is not a failing test, it is a VACUOUS passing one. That is
 * why enabling belongs inside this helper rather than at the call sites that must remember it.
 */
public final class HibernateStatistics {

    private HibernateStatistics() {
    }

    /**
     * The {@link Statistics} of the {@link SessionFactory} behind {@code emf}, with collection
     * enabled. Callers take a baseline (typically {@code getPrepareStatementCount()}) immediately
     * before the action under test and diff it immediately after.
     */
    public static Statistics enabledOn(EntityManagerFactory emf) {
        Statistics statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        return statistics;
    }
}
