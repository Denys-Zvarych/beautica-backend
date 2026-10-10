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
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;

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
    /**
     * {@code socketTimeout} is Apache HC5's SO_TIMEOUT: an INACTIVITY timeout applied to each
     * blocking socket read (time with no bytes received), NOT a total-call deadline. A 5 MB upload
     * (see {@code spring.servlet.multipart.max-file-size}) on a slow mobile link therefore cannot be
     * cut off while bytes keep flowing; only a stalled R2 socket trips it. No
     * {@code apiCallTimeout}/{@code apiCallAttemptTimeout} is set on purpose — a total deadline
     * could abort a legitimate slow upload. Package-private so the unit test pins the values.
     */
    static final Duration R2_SOCKET_TIMEOUT = Duration.ofSeconds(30);
    static final Duration R2_CONNECTION_TIMEOUT = Duration.ofSeconds(5);
    static final int R2_MAX_CONNECTIONS = 50;

    /** Hosts accepted for {@code app.cloudflare-r2.endpoint} unless the opt-out property is set. */
    static final String R2_HOST_SUFFIX = ".r2.cloudflarestorage.com";

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
     * @param endpointOverride optional endpoint override (e.g. an EU-jurisdiction bucket
     *                         {@code https://<acct>.eu.r2.cloudflarestorage.com}); blank falls
     *                         back to the account-id template; must be {@code https://}
     * @param allowCustomEndpoint opt-out of the {@code *.r2.cloudflarestorage.com} host rule
     *                         ({@code app.cloudflare-r2.allow-custom-endpoint}, default false)
     * @param bucket          bucket name, used only for the one-shot startup log line
     * @param publicUrl       public bucket URL, only its host is logged
     * @throws IllegalStateException if the feature is enabled but any credential is blank —
     *                               surfaces deployment misconfiguration immediately on boot
     */
    @Bean
    @ConditionalOnProperty(prefix = "app.cloudflare-r2", name = "enabled", havingValue = "true")
    public S3Client s3Client(
            @Value("${app.cloudflare-r2.account-id:}") String accountId,
            @Value("${app.cloudflare-r2.access-key-id:}") String accessKeyId,
            @Value("${app.cloudflare-r2.secret-access-key:}") String secretAccessKey,
            @Value("${app.cloudflare-r2.endpoint:}") String endpointOverride,
            @Value("${app.cloudflare-r2.allow-custom-endpoint:false}") boolean allowCustomEndpoint,
            @Value("${app.cloudflare-r2.bucket:}") String bucket,
            @Value("${app.cloudflare-r2.public-url:}") String publicUrl
    ) {
        requireConfigured("app.cloudflare-r2.account-id", accountId);
        requireConfigured("app.cloudflare-r2.access-key-id", accessKeyId);
        requireConfigured("app.cloudflare-r2.secret-access-key", secretAccessKey);

        log.info("R2 enabled: bucket={}, publicHost={}", bucket, hostOf(publicUrl));

        return configure(S3Client.builder(), accountId, accessKeyId, secretAccessKey, endpointOverride,
                allowCustomEndpoint).build();
    }

    /**
     * Applies every R2-specific setting to the given builder. Package-private so the unit test
     * can drive it with a mock builder and assert the checksum policy (the built {@link S3Client}
     * exposes no getters for it).
     */
    static S3ClientBuilder configure(
            S3ClientBuilder builder,
            String accountId,
            String accessKeyId,
            String secretAccessKey,
            String endpointOverride
    ) {
        return configure(builder, accountId, accessKeyId, secretAccessKey, endpointOverride, false);
    }

    static S3ClientBuilder configure(
            S3ClientBuilder builder,
            String accountId,
            String accessKeyId,
            String secretAccessKey,
            String endpointOverride,
            boolean allowCustomEndpoint
    ) {
        URI endpoint = resolveEndpoint(accountId, endpointOverride, allowCustomEndpoint);
        AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKeyId, secretAccessKey);

        return builder
                .endpointOverride(endpoint)
                .region(R2_REGION)
                // Cloudflare R2 rejects the default CRC32 trailer/streaming checksums that
                // AWS SDK >= 2.30 sends — compute/validate only when the API requires it.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                // Explicit Apache HC5 tuning — defaults leave socketTimeout=0 (infinite),
                // which would pin Tomcat worker threads on a hung R2 socket. See Phase 7.2
                // perf audit; this closes the tracked LOW.
                .httpClientBuilder(r2HttpClientBuilder());
    }

    /** Package-private seam so tests can inspect the timeouts and pool size actually configured. */
    static ApacheHttpClient.Builder r2HttpClientBuilder() {
        return ApacheHttpClient.builder()
                .socketTimeout(R2_SOCKET_TIMEOUT)
                .connectionTimeout(R2_CONNECTION_TIMEOUT)
                .maxConnections(R2_MAX_CONNECTIONS);
    }

    private static URI resolveEndpoint(String accountId, String endpointOverride, boolean allowCustomEndpoint) {
        if (endpointOverride == null || endpointOverride.isBlank()) {
            return URI.create(String.format(R2_ENDPOINT_TEMPLATE, accountId));
        }
        String trimmed = endpointOverride.trim();
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException ex) {
            throw new IllegalStateException(
                    "app.cloudflare-r2.endpoint is not a valid URI with a host; expected https://<host>");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalStateException(
                    "app.cloudflare-r2.endpoint must start with https:// when set");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalStateException(
                    "app.cloudflare-r2.endpoint must include a host (https://<host>)");
        }
        if (!allowCustomEndpoint && !host.toLowerCase(Locale.ROOT).endsWith(R2_HOST_SUFFIX)) {
            throw new IllegalStateException(
                    "app.cloudflare-r2.endpoint host must end with " + R2_HOST_SUFFIX
                            + " (set app.cloudflare-r2.allow-custom-endpoint=true to override)");
        }
        return uri;
    }

    private static String hostOf(String url) {
        try {
            String host = URI.create(url == null ? "" : url.trim()).getHost();
            return host == null ? "" : host;
        } catch (IllegalArgumentException ex) {
            return "";
        }
    }

    private static void requireConfigured(String propertyName, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Cloudflare R2 is enabled (app.cloudflare-r2.enabled=true) but "
                            + propertyName + " is not configured");
        }
    }
}
