package com.beautica.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.time.Duration;

/**
 * Wires the Cloudflare R2 {@link S3Client} bean for media uploads.
 *
 * <p>Activation policy mirrors {@link FirebaseConfig}'s feature-flag pattern but takes a
 * stricter route: there is no useful no-op {@code S3Client}, so the bean is registered only
 * when {@code app.cloudflare-r2.enabled=true}. When the flag is false (the safe default),
 * Spring never creates the bean and {@link com.beautica.media.R2StorageService} (Phase 7.4)
 * will inject {@code Optional<S3Client>} and short-circuit upload calls.
 *
 * <p><b>Fail-fast on misconfiguration:</b> if the operator sets {@code R2_ENABLED=true} but
 * leaves any required credential blank, the bean factory throws {@link IllegalStateException}
 * at startup. This is intentional — silently degrading to "uploads disabled" in production
 * would mask a deployment error.
 */
@Slf4j
@Configuration
public class S3Config {

    private static final String R2_ENDPOINT_TEMPLATE = "https://%s.r2.cloudflarestorage.com";

    /** R2 region alias — Cloudflare ignores this value but the SDK requires one. */
    private static final Region R2_REGION = Region.of("auto");

    /**
     * Apache HC5 timeouts and pool size for the sync S3 client.
     *
     * <p>Closes Phase 7.2 perf LOW: AWS SDK v2's default {@code httpClientBuilder} leaves
     * {@code socketTimeout=0} (infinite). A hung R2 TCP socket would pin a request thread
     * forever, eventually exhausting the Tomcat worker pool. Explicit timeouts let the SDK
     * fail fast and free the thread.
     *
     * <p>{@code maxConnections} mirrors the SDK default — declared here for visibility so a
     * future bump (e.g. for Phase 7.5 portfolio batch uploads) lands in one place.
     */
    private static final Duration R2_SOCKET_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration R2_CONNECTION_TIMEOUT = Duration.ofSeconds(5);
    private static final int R2_MAX_CONNECTIONS = 50;

    @Value("${app.cloudflare-r2.enabled:false}")
    private boolean r2Enabled;

    /**
     * Logs a single startup warning when the feature is disabled so operators can confirm the
     * intended state from the boot log without scanning property files. Runs unconditionally —
     * the {@link ConditionalOnProperty} only gates the {@link #s3Client(String, String, String)}
     * bean method, not the {@code @Configuration} class itself.
     */
    @PostConstruct
    void announceState() {
        if (!r2Enabled) {
            log.warn("Cloudflare R2 is disabled (app.cloudflare-r2.enabled=false) — "
                    + "S3Client bean will not be registered and media uploads will be suppressed");
        }
    }

    /**
     * Builds the singleton {@link S3Client} for Cloudflare R2 only when the feature flag is on
     * and every credential is non-blank. The bean is annotated with
     * {@link ConditionalOnProperty} so callers in disabled environments inject
     * {@code Optional<S3Client>} cleanly.
     *
     * @param accountId       Cloudflare account ID; combined with the R2 endpoint template
     * @param accessKeyId     R2 access key (analog of AWS access key)
     * @param secretAccessKey R2 secret (analog of AWS secret access key)
     * @throws IllegalStateException if the feature is enabled but any credential is blank —
     *                               surfaces deployment misconfiguration immediately on boot
     */
    @Bean
    @ConditionalOnProperty(prefix = "app.cloudflare-r2", name = "enabled", havingValue = "true")
    public S3Client s3Client(
            @Value("${app.cloudflare-r2.account-id:}") String accountId,
            @Value("${app.cloudflare-r2.access-key-id:}") String accessKeyId,
            @Value("${app.cloudflare-r2.secret-access-key:}") String secretAccessKey
    ) {
        requireConfigured("app.cloudflare-r2.account-id", accountId);
        requireConfigured("app.cloudflare-r2.access-key-id", accessKeyId);
        requireConfigured("app.cloudflare-r2.secret-access-key", secretAccessKey);

        log.info("Cloudflare R2 enabled — registering S3Client");

        URI endpoint = URI.create(String.format(R2_ENDPOINT_TEMPLATE, accountId));
        AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKeyId, secretAccessKey);

        return S3Client.builder()
                .endpointOverride(endpoint)
                .region(R2_REGION)
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                // Explicit Apache HC5 tuning — defaults leave socketTimeout=0 (infinite),
                // which would pin Tomcat worker threads on a hung R2 socket. See Phase 7.2
                // perf audit; this closes the tracked LOW.
                .httpClientBuilder(ApacheHttpClient.builder()
                        .socketTimeout(R2_SOCKET_TIMEOUT)
                        .connectionTimeout(R2_CONNECTION_TIMEOUT)
                        .maxConnections(R2_MAX_CONNECTIONS))
                .build();
    }

    private static void requireConfigured(String propertyName, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Cloudflare R2 is enabled (app.cloudflare-r2.enabled=true) but "
                            + propertyName + " is not configured");
        }
    }
}
