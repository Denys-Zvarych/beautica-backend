package com.beautica.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>Config-drift guard (2026-09-02) — {@code application-test.yml}'s invite rate-limit
 * overrides must stay far above the invite-endpoint tests' real per-IP call volume.</b>
 *
 * <p>{@code InviteControllerIT} alone fires several dozen real HTTP calls against
 * {@code GET /api/v1/auth/invite/validate} and {@code POST /api/v1/auth/invite/accept} from
 * 127.0.0.1, all sharing one per-IP bucket each ({@code inviteValidateBuckets} /
 * {@code inviteAcceptBuckets} in {@link RateLimitConfig}); {@code
 * InviteAcceptRejectsRevokedIntegrationTest} and {@code
 * InviteAcceptRejectsInactiveSalonIntegrationTest} add more against the same two buckets. The
 * production defaults (30 requests / 60 s, 20 requests / 15 min —
 * {@code RateLimitConfig#inviteValidateCapacity} / {@code #inviteAcceptCapacity}) are well below
 * that combined volume, so {@code src/test/resources/application-test.yml} raises both to
 * 100 000 for the {@code test} profile, mirroring every other auth rate-limit bucket in that
 * file.
 *
 * <p><b>Why this test exists.</b> Nothing else in the suite catches a future edit that lowers
 * those two YAML keys back toward production-realistic values — a plausible-looking "tests
 * shouldn't need a bypass this generous" cleanup. That edit would not fail fast: it would make
 * {@code InviteControllerIT} and its siblings start intermittently 429ing, exactly the failure
 * mode the class javadocs on {@code InviteValidateGetRateLimitTest} /
 * {@code InviteAcceptPostRateLimitRegressionTest} describe as having already happened once for
 * an equivalent bucket. This test reads the ACTUAL committed YAML value at test time — not a
 * hardcoded copy of it — so an edit to the config file itself, not just to production code,
 * trips this test before it can reintroduce that flake. The floor (1000) is roughly 20x the
 * classes' combined real call volume: comfortably below the production 100 000 override (so a
 * deliberate, reasoned reduction of the override is still possible) but comfortably above
 * anything the current or a modestly-grown invite IT suite could ever consume.
 */
@DisplayName("application-test.yml — invite rate-limit capacities stay inflated for the invite ITs")
class InviteRateLimitTestProfileCapacityTest {

    private static final long MINIMUM_SAFE_CAPACITY = 1000L;

    @Test
    @DisplayName("should_stayFarAboveRealCallVolume_when_inviteValidateCapacityRead")
    void should_stayFarAboveRealCallVolume_when_inviteValidateCapacityRead() {
        assertThat(readCapacity("invite-validate-capacity"))
                .as("app.rate-limit.invite-validate-capacity in application-test.yml must stay far "
                        + "above InviteControllerIT's real per-IP call volume against GET "
                        + "/auth/invite/validate, or that class will start intermittently 429ing")
                .isGreaterThanOrEqualTo(MINIMUM_SAFE_CAPACITY);
    }

    @Test
    @DisplayName("should_stayFarAboveRealCallVolume_when_inviteAcceptCapacityRead")
    void should_stayFarAboveRealCallVolume_when_inviteAcceptCapacityRead() {
        assertThat(readCapacity("invite-accept-capacity"))
                .as("app.rate-limit.invite-accept-capacity in application-test.yml must stay far "
                        + "above the combined real per-IP call volume of InviteControllerIT, "
                        + "InviteAcceptRejectsRevokedIntegrationTest and "
                        + "InviteAcceptRejectsInactiveSalonIntegrationTest against POST "
                        + "/auth/invite/accept, or those classes will start intermittently 429ing")
                .isGreaterThanOrEqualTo(MINIMUM_SAFE_CAPACITY);
    }

    /**
     * Parses the real, committed {@code application-test.yml} from the test classpath (the same
     * file Spring Boot loads for {@code @ActiveProfiles("test")}) and returns the numeric value
     * of {@code app.rate-limit.<key>} — never a value this test constructed itself, so an edit to
     * the file is what this test is actually exercising.
     */
    private static long readCapacity(String key) {
        var factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource("application-test.yml"));
        Properties props = factory.getObject();
        assertThat(props).as("application-test.yml must parse into properties").isNotNull();

        String raw = props.getProperty("app.rate-limit." + key);
        assertThat(raw)
                .as("app.rate-limit.%s must be present in application-test.yml", key)
                .isNotNull();
        return Long.parseLong(raw.trim());
    }
}
