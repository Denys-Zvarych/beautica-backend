package com.beautica.booking.service;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs a booking write path's side-effects (cache eviction, SMS) AFTER the transaction commits.
 *
 * <p><b>Why after commit and not inline</b> (Anti-Bug §F2): an inline evict runs before the DB sees
 * the write, so a parallel reader can repopulate the availability cache with the pre-write answer
 * and keep serving a slot the booking just consumed for the whole 60s TTL. Registering an
 * {@link TransactionSynchronization#afterCommit()} callback closes that window; a rolled-back write
 * evicts nothing, which is also correct.
 *
 * <p><b>The no-transaction branch is load-bearing for tests, not production.</b> Every caller is
 * {@code @Transactional}, so in the running application synchronization is always active. A plain
 * Mockito unit test invokes the service method directly with no transaction, and
 * {@code TransactionSynchronizationManager.registerSynchronization} throws
 * {@code IllegalStateException} in that state — running the task immediately keeps those tests
 * asserting the eviction rather than exploding on the registration.
 *
 * <p>Static utility in the same package as its callers, mirroring {@link BookingSlotAvailabilityGuard}
 * and {@link BookingSlotLockGuard} — see the former's Javadoc for why a bean would risk a cycle.
 */
final class BookingAfterCommit {

    private BookingAfterCommit() {
    }

    static void run(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }
}
