package com.beautica.config;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the R2 socket timeout built by {@link S3Config#r2HttpClientBuilder} actually FIRES.
 *
 * <p>The client is assembled directly rather than through {@code S3Config.configure(...)}: that
 * method rejects non-{@code https} endpoints, and WireMock is plain HTTP here. The HTTP client
 * builder under test is the exact production helper. SDK retries are disabled so elapsed time
 * measures one timeout, not retries x timeout.
 */
@DisplayName("S3Config — R2 socket timeout fires")
class S3ConfigTimeoutTest {

    private WireMockServer wireMock;
    private S3Client client;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        client = S3Client.builder()
                .endpointOverride(URI.create(wireMock.baseUrl()))
                .region(Region.of("auto"))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("k", "s")))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .overrideConfiguration(o -> o.retryPolicy(RetryPolicy.none()))
                .httpClientBuilder(S3Config.r2HttpClientBuilder(Duration.ofSeconds(5), Duration.ofMillis(300)))
                .build();
    }

    @AfterEach
    void tearDown() {
        client.close();
        wireMock.stop();
    }

    @Test
    @Timeout(5)
    @DisplayName("should_failWithSocketTimeout_when_r2DelaysResponsePastSocketTimeout")
    void should_failWithSocketTimeout_when_r2DelaysResponsePastSocketTimeout() {
        wireMock.stubFor(put(urlPathMatching("/bucket/.*"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(2000)));

        long start = System.nanoTime();
        assertThatThrownBy(() -> client.putObject(
                b -> b.bucket("bucket").key("k.txt"), RequestBody.fromString("x")))
                .isInstanceOf(SdkClientException.class)
                .satisfies(e -> assertThat(causeChain(e)).anyMatch(t -> t instanceof SocketTimeoutException));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(elapsed).isLessThan(Duration.ofMillis(1500));
    }

    private static java.util.List<Throwable> causeChain(Throwable t) {
        java.util.List<Throwable> chain = new java.util.ArrayList<>();
        for (Throwable c = t; c != null && !chain.contains(c); c = c.getCause()) {
            chain.add(c);
        }
        return chain;
    }
}
