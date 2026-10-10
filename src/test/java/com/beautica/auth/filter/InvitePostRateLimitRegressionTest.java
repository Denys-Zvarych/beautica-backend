package com.beautica.auth.filter;

import com.beautica.config.RateLimitConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BandwidthBuilder;
import io.github.bucket4j.Bucket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>SEC-FIX REGRESSION NET — per-IP throttle on POST /api/v1/auth/invite (timing/enumeration +
 * invite-email flood guard).</b>
 *
 * <p>{@code POST /api/v1/auth/invite} is the residual side-channel left after the
 * {@code InviteService} 409-&gt;idempotent fix: the already-registered and active-invite branches
 * do measurably less work than the brand-new branch, so without an IP-layer throttle an
 * authenticated {@code SALON_OWNER} could gather enough timing samples to infer registration
 * status; the happy path also enqueues an invite e-mail, making this an e-mail flood surface too.
 * {@link AuthRateLimitFilter} caps these POSTs per IP via the injected {@code inviteBuckets} bean
 * ({@link RateLimitConfig#inviteBuckets()}), sized by {@code app.rate-limit.invite-capacity}
 * (default 15 / 60 s).
 *
 * <p>The invite bucket is built by a REAL {@link RateLimitConfig} bound through Spring's
 * property resolution ({@link ApplicationContextRunner}) — so the {@code @Value} default and an
 * override are both exercised end-to-end, never a hand-built bucket that would merely restate
 * the expected number. The filter is then driven through {@code doFilterInternal} and only
 * observable HTTP behaviour is asserted. Every other constructor position gets a permissive cache.
 */
@DisplayName("AuthRateLimitFilter — POST /auth/invite per-IP throttle (SEC-fix enumeration/timing regression net)")
class InvitePostRateLimitRegressionTest {

    private static final String REMOTE_ADDR = "10.0.0.77";
    private static final String INVITE_PATH = "/api/v1/auth/invite";
    private static final int DEFAULT_CAP = 15;
    private static final int OVERRIDE_CAP = 3;
    // Fired well above the 15/min default so the bucket is exhausted regardless of the exact capacity.
    private static final int REQUESTS_TO_FIRE = 40;

    private static LoadingCache<String, Bucket> permissive() {
        return Caffeine.newBuilder().build(key -> Bucket.builder()
                .addLimit(unlimited())
                .build());
    }

    private static Bandwidth unlimited() {
        return BandwidthBuilder.builder()
                .capacity(1_000_000)
                .refillIntervally(1_000_000, Duration.ofMinutes(1))
                .build();
    }

    /**
     * Binds a real {@link RateLimitConfig} with the given extra properties and returns its
     * {@code inviteBuckets} bean. {@code ObjectMapper} is registered only because the config's
     * {@code bookingRateLimitFilter} bean method needs one.
     */
    @SuppressWarnings("unchecked")
    private static LoadingCache<String, Bucket> configuredInviteBuckets(String... properties) {
        AtomicReference<LoadingCache<String, Bucket>> ref = new AtomicReference<>();
        new ApplicationContextRunner()
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withUserConfiguration(RateLimitConfig.class)
                .withPropertyValues(properties)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    ref.set((LoadingCache<String, Bucket>) ctx.getBean("inviteBuckets", LoadingCache.class));
                });
        return ref.get();
    }

    private static AuthRateLimitFilter realFilter(LoadingCache<String, Bucket> inviteBuckets) {
        // 22 permissive caches — one positional arg per @Qualifier bucket on the production
        // constructor — followed by the real, config-built inviteBuckets in the 23rd position.
        return new AuthRateLimitFilter(
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(), permissive(), permissive(),
                inviteBuckets);
    }

    /** Fires up to {@link #REQUESTS_TO_FIRE} invites; returns how many passed before the first 429. */
    private int allowedBeforeThrottle(AuthRateLimitFilter filter) throws Exception {
        for (int i = 0; i < REQUESTS_TO_FIRE; i++) {
            var response = new MockHttpServletResponse();
            var chain = new MockFilterChain();
            filter.doFilterInternal(postInvite(), response, chain);
            if (response.getStatus() == 429) {
                assertThat(chain.getRequest())
                        .as("the throttled invite request must NOT be forwarded down the filter chain")
                        .isNull();
                return i;
            }
        }
        return REQUESTS_TO_FIRE;
    }

    private MockHttpServletRequest postInvite() {
        var req = new MockHttpServletRequest("POST", INVITE_PATH);
        req.setRemoteAddr(REMOTE_ADDR);
        return req;
    }

    @Test
    @DisplayName("should_return429OnSixteenthRequest_when_inviteCapacityLeftAtDefault")
    void should_return429OnSixteenthRequest_when_inviteCapacityLeftAtDefault() throws Exception {
        AuthRateLimitFilter filter = realFilter(configuredInviteBuckets());

        int allowed = allowedBeforeThrottle(filter);

        assertThat(allowed)
                .as("with no app.rate-limit.invite-capacity set, the @Value default must be the "
                        + "documented %d/min ceiling — request %d is the first throttled one",
                        DEFAULT_CAP, DEFAULT_CAP + 1)
                .isEqualTo(DEFAULT_CAP);
    }

    @Test
    @DisplayName("should_return429OnFourthRequest_when_inviteCapacityOverriddenToThree")
    void should_return429OnFourthRequest_when_inviteCapacityOverriddenToThree() throws Exception {
        AuthRateLimitFilter filter = realFilter(
                configuredInviteBuckets("app.rate-limit.invite-capacity=" + OVERRIDE_CAP));

        int allowed = allowedBeforeThrottle(filter);

        assertThat(allowed)
                .as("app.rate-limit.invite-capacity=%d must be honoured — request %d is the first "
                        + "throttled one", OVERRIDE_CAP, OVERRIDE_CAP + 1)
                .isEqualTo(OVERRIDE_CAP);
    }

    @Test
    @DisplayName("should_pinInviteCapacityToFifteen_when_applicationYmlRead")
    void should_pinInviteCapacityToFifteen_when_applicationYmlRead() {
        var factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource("application.yml"));

        Properties props = factory.getObject();

        assertThat(props).isNotNull();
        assertThat(props.getProperty("app.rate-limit.invite-capacity"))
                .as("the base application.yml (inherited by prod) must keep POST /auth/invite at 15/min")
                .isEqualTo(String.valueOf(DEFAULT_CAP));
    }

    @Test
    @DisplayName("should_setRetryAfterHeader_when_invitePostThrottled")
    void should_setRetryAfterHeader_when_invitePostThrottled() throws Exception {
        AuthRateLimitFilter filter = realFilter(
                configuredInviteBuckets("app.rate-limit.invite-capacity=" + OVERRIDE_CAP));
        for (int i = 0; i < OVERRIDE_CAP; i++) {
            filter.doFilterInternal(postInvite(), new MockHttpServletResponse(), new MockFilterChain());
        }
        var throttled = new MockHttpServletResponse();

        filter.doFilterInternal(postInvite(), throttled, new MockFilterChain());

        assertThat(throttled.getStatus()).isEqualTo(429);
        assertThat(throttled.getHeader("Retry-After"))
                .as("a throttled invite must carry the 60-second-window Retry-After so clients back off")
                .isEqualTo("60");
        assertThat(throttled.getContentType())
                .as("the 429 body is the JSON too-many-requests envelope")
                .contains("application/json");
    }
}
