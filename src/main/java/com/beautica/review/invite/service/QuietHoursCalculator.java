package com.beautica.review.invite.service;

import com.beautica.common.TimeZones;
import com.beautica.review.invite.config.ReviewInviteProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;

/**
 * Phase 400 D3 — when a review invite may go out. {@code send_after = completedAt + delay}; a candidate whose Kyiv
 * wall-clock time falls in {@code [quietStart, quietEnd)} (default 21:00–09:00) moves to the next {@code sendAt}
 * (default 10:00): the first one after the candidate (07:30 → same day, 21:30 → next day). The send job re-applies
 * {@link #isQuiet} at send time, so a backlog after downtime never goes out at night.
 *
 * <p>Pure instant arithmetic in {@code Europe/Kyiv}: the delay is added on the timeline (a DST jump does not stretch
 * or shrink it) and the target is resolved with {@link ZonedDateTime#of}, which shifts a time inside a spring-forward
 * gap forward and takes the earlier offset in an autumn overlap.
 */
@Component
public class QuietHoursCalculator {

    private final ReviewInviteProperties properties;

    public QuietHoursCalculator(ReviewInviteProperties properties) {
        this.properties = properties;
    }

    /** D3 — the {@code send_after} for a visit whose first booking was completed at {@code completedAt}. */
    public Instant sendAfter(Instant completedAt) {
        return nextAllowed(completedAt.plus(properties.delay()));
    }

    /** {@code candidate} itself when outside the quiet window, else the next {@code sendAt} in Kyiv. */
    public Instant nextAllowed(Instant candidate) {
        ZonedDateTime local = candidate.atZone(TimeZones.KYIV);
        if (!isQuiet(local.toLocalTime())) {
            return candidate;
        }
        // The first sendAt strictly after the candidate's wall-clock time: 07:30 → 10:00 the same day,
        // 21:00 → 10:00 the next day.
        boolean sameDay = local.toLocalTime().isBefore(properties.sendAt());
        ZonedDateTime target = ZonedDateTime.of(
                local.toLocalDate().plusDays(sameDay ? 0 : 1), properties.sendAt(), TimeZones.KYIV);
        return target.toInstant();
    }

    /** True when {@code now} is inside the Kyiv quiet window — the send job sends nothing then. */
    public boolean isQuiet(Instant now) {
        return isQuiet(now.atZone(TimeZones.KYIV).toLocalTime());
    }

    private boolean isQuiet(LocalTime time) {
        return ReviewInviteProperties.isQuiet(time, properties.quietStart(), properties.quietEnd());
    }
}
