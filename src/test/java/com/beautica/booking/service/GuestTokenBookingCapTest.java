package com.beautica.booking.service;

import com.beautica.auth.phoneotp.GuestTokenProvider.GuestTokenClaims;
import com.beautica.common.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase 393 audit — {@link GuestTokenBookingCap}: 10 per token, per-jti isolation, rollback release, atomicity. */
@DisplayName("GuestTokenBookingCap — unit")
class GuestTokenBookingCapTest {

    private static final String PHONE = "+380671234567";

    private final GuestTokenBookingCap cap = new GuestTokenBookingCap();

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("10 reservations per token pass, the 11th is a 429")
    void should_throw429_when_eleventhBookingOnSameToken() {
        GuestTokenClaims token = new GuestTokenClaims(PHONE, "jti-a");
        for (int i = 0; i < GuestTokenBookingCap.MAX_BOOKINGS_PER_TOKEN; i++) {
            cap.reserve(token);
        }

        assertThatThrownBy(() -> cap.reserve(token))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("a fresh token (new OTP → new jti) for the same phone has its own budget")
    void should_admitNewToken_when_previousTokenExhausted() {
        exhaust(new GuestTokenClaims(PHONE, "jti-a"));

        assertThatCode(() -> cap.reserve(new GuestTokenClaims(PHONE, "jti-b"))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("legacy token without jti is capped by its phone")
    void should_capByPhone_when_tokenHasNoJti() {
        exhaust(new GuestTokenClaims(PHONE, null));

        assertThatThrownBy(() -> cap.reserve(new GuestTokenClaims(PHONE, null)))
                .isInstanceOf(BusinessException.class);
        assertThatCode(() -> cap.reserve(new GuestTokenClaims("+380501112233", null))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a rolled-back booking releases its reservation; a committed one keeps it")
    void should_releaseReservation_when_transactionRollsBack() {
        GuestTokenClaims token = new GuestTokenClaims(PHONE, "jti-rb");
        for (int i = 0; i < GuestTokenBookingCap.MAX_BOOKINGS_PER_TOKEN - 1; i++) {
            cap.reserve(token);
        }
        TransactionSynchronizationManager.initSynchronization();
        cap.reserve(token);
        completeAll(TransactionSynchronization.STATUS_ROLLED_BACK);

        cap.reserve(token);
        completeAll(TransactionSynchronization.STATUS_COMMITTED);

        assertThatThrownBy(() -> cap.reserve(token)).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("concurrent replays of one token admit exactly 10 (atomic reserve, no check-then-act race)")
    void should_admitExactlyTen_when_fiftyConcurrentReplays() throws Exception {
        GuestTokenClaims token = new GuestTokenClaims(PHONE, "jti-race");
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger admitted = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < 50; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    cap.reserve(token);
                    admitted.incrementAndGet();
                } catch (BusinessException expected) {
                    // over the cap
                } catch (Throwable t) {
                    unexpected.add(t);
                }
            });
        }

        start.countDown();
        pool.shutdown();
        boolean finished = pool.awaitTermination(10, TimeUnit.SECONDS);

        assertThat(finished).isTrue();
        assertThat(unexpected).isEmpty();
        assertThat(admitted.get()).isEqualTo(GuestTokenBookingCap.MAX_BOOKINGS_PER_TOKEN);
    }

    private void exhaust(GuestTokenClaims token) {
        for (int i = 0; i < GuestTokenBookingCap.MAX_BOOKINGS_PER_TOKEN; i++) {
            cap.reserve(token);
        }
    }

    private static void completeAll(int status) {
        List<TransactionSynchronization> registered = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();
        registered.forEach(sync -> sync.afterCompletion(status));
        TransactionSynchronizationManager.initSynchronization();
    }
}
