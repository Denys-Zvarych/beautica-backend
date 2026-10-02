package com.beautica.booking.service;

import com.beautica.booking.repository.BookingRepository;
import com.beautica.booking.repository.TestPostLockSlotCheck;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.NotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Unit pin for {@link PostLockSlotGuard#assertStillFreeAfterLock} — in particular the fail-closed
 * handling of a {@code null} {@link com.beautica.booking.repository.PostLockSlotCheck} column (LOW
 * security, Phase 337 QA follow-up: the getters used to be primitive {@code boolean}, so a SQL
 * {@code NULL} from the {@code LEFT JOIN salons} would have thrown an unboxing
 * {@code NullPointerException} instead of the intended 404/409).
 *
 * <p>A {@code null} is not reachable from the real {@code LEFT JOIN}/{@code EXISTS} query today (see
 * {@link com.beautica.booking.repository.PostLockSlotCheck}'s javadoc), so these are defence-in-depth
 * tests against a future driver/query-plan change or a hand-rolled {@link
 * com.beautica.booking.repository.PostLockSlotCheck} implementation reintroducing one — not a
 * regression pin for an observed production bug.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PostLockSlotGuard — fail-closed on a null bookability/overlap column")
class PostLockSlotGuardTest {

    @Mock
    private BookingRepository bookingRepository;

    private final UUID masterId = UUID.randomUUID();
    private final OffsetDateTime startsAt = OffsetDateTime.parse("2026-06-01T10:00:00Z");
    private final OffsetDateTime endsAt = OffsetDateTime.parse("2026-06-01T11:00:00Z");

    @Test
    @DisplayName("passes when the master is bookable and no overlap exists")
    void should_pass_when_masterBookableTrueAndOverlapFalse() {
        when(bookingRepository.findPostLockBookabilityAndOverlap(masterId, startsAt, endsAt))
                .thenReturn(Optional.of(new TestPostLockSlotCheck(true, false)));

        assertThatCode(() -> PostLockSlotGuard.assertStillFreeAfterLock(
                bookingRepository, masterId, startsAt, endsAt))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("404s when no row for the master exists at all")
    void should_throwNotFound_when_checkAbsent() {
        when(bookingRepository.findPostLockBookabilityAndOverlap(masterId, startsAt, endsAt))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> PostLockSlotGuard.assertStillFreeAfterLock(
                bookingRepository, masterId, startsAt, endsAt))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Master not found or inactive");
    }

    @Test
    @DisplayName("404s when the master is explicitly not bookable")
    void should_throwNotFound_when_masterBookableFalse() {
        when(bookingRepository.findPostLockBookabilityAndOverlap(masterId, startsAt, endsAt))
                .thenReturn(Optional.of(new TestPostLockSlotCheck(false, false)));

        assertThatThrownBy(() -> PostLockSlotGuard.assertStillFreeAfterLock(
                bookingRepository, masterId, startsAt, endsAt))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Master not found or inactive");
    }

    @Test
    @DisplayName("404s (fail-closed) when master_bookable comes back SQL NULL, rather than throwing "
            + "an unboxing NullPointerException")
    void should_throwNotFound_when_masterBookableIsNull() {
        when(bookingRepository.findPostLockBookabilityAndOverlap(masterId, startsAt, endsAt))
                .thenReturn(Optional.of(new TestPostLockSlotCheck(null, false)));

        assertThatThrownBy(() -> PostLockSlotGuard.assertStillFreeAfterLock(
                bookingRepository, masterId, startsAt, endsAt))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Master not found or inactive");
    }

    @Test
    @DisplayName("409s when the master is bookable but the window overlaps a CONFIRMED booking")
    void should_throwConflict_when_overlapExistsTrue() {
        when(bookingRepository.findPostLockBookabilityAndOverlap(masterId, startsAt, endsAt))
                .thenReturn(Optional.of(new TestPostLockSlotCheck(true, true)));

        assertThatThrownBy(() -> PostLockSlotGuard.assertStillFreeAfterLock(
                bookingRepository, masterId, startsAt, endsAt))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Slot not available")
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("409s (fail-closed) when overlap_exists comes back SQL NULL, rather than throwing "
            + "an unboxing NullPointerException")
    void should_throwConflict_when_overlapExistsIsNull() {
        when(bookingRepository.findPostLockBookabilityAndOverlap(masterId, startsAt, endsAt))
                .thenReturn(Optional.of(new TestPostLockSlotCheck(true, null)));

        assertThatThrownBy(() -> PostLockSlotGuard.assertStillFreeAfterLock(
                bookingRepository, masterId, startsAt, endsAt))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Slot not available")
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
    }
}
