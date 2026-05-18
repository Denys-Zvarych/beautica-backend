package com.beautica.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1.8 — source-code guard tests for verification security invariants.
 *
 * <p>These tests run without a Spring context (no DB, no network) and scan the
 * production source tree to enforce invariants that would be hard to catch at runtime:
 * <ul>
 *   <li>Constant-time OTP comparison ({@code MessageDigest.isEqual}, not {@code String.equals})</li>
 *   <li>CSPRNG for OTP generation ({@code SecureRandom}, not {@code Math.random} / {@code new Random()})</li>
 *   <li>Log hygiene — OTP value ({@code rawOtp}) never appears in log statements</li>
 * </ul>
 *
 * <p>HTTP-level rate-limit guard tests (§1) are in {@code AuthRateLimitFilterTest}:
 * {@code should_return429_when_verifyEmailBucketExhausted} and
 * {@code should_return429_when_resendVerificationRateLimitExceeded}.
 *
 * <p>Service-level anti-enumeration and expiry/clear tests (§5–§6) are in
 * {@code AuthServiceTest}: {@code should_throwInvalidCode_when_emailUnknown},
 * {@code should_throwCodeExpired_when_codeExpired},
 * {@code resendVerification — returns generic response for unknown email},
 * {@code resendVerification — returns generic response for already-verified user}.
 */
@DisplayName("Phase 1.8 — Verification security guard tests")
class VerificationSecurityGuardTest {

    private static final Path PROJECT_ROOT = Paths.get(System.getProperty("user.dir"));
    private static final Path AUTH_SRC     = PROJECT_ROOT.resolve("src/main/java/com/beautica/auth");
    private static final Path NOTIF_SRC    = PROJECT_ROOT.resolve("src/main/java/com/beautica/notification");
    private static final Path EXC_SRC      = PROJECT_ROOT.resolve("src/main/java/com/beautica/common/exception");

    // ──────────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("§2 — Constant-time comparison guard")
    class ConstantTimeComparisonGuard {

        @Test
        @DisplayName("should_useMessageDigestIsEqual_when_comparingOtp")
        void should_useMessageDigestIsEqual_when_comparingOtp() throws IOException {
            // The locked OTP critical section was extracted from AuthService into
            // EmailVerificationProcessor (a this.-call to a @Transactional method
            // bypasses the proxy — Anti-Bug §F3). The constant-time guard now
            // pins the processor where the compare actually lives.
            String source = Files.readString(AUTH_SRC.resolve("EmailVerificationProcessor.java"));

            assertThat(source)
                    .as("EmailVerificationProcessor must use MessageDigest.isEqual for OTP hash comparison — "
                            + "String.equals exits early on first mismatch, creating a timing oracle on a 6-digit secret")
                    .contains("MessageDigest.isEqual");

            assertThat(source)
                    .as("OTP comparison must not call request.code().equals() — timing oracle risk")
                    .doesNotContain("request.code().equals(");

            // Neither AuthService nor the processor may fall back to String.equals
            // on the stored hash anywhere in the verification path.
            String authServiceSource = Files.readString(AUTH_SRC.resolve("AuthService.java"));
            assertThat(authServiceSource)
                    .as("AuthService must not compare the OTP via String.equals")
                    .doesNotContain("request.code().equals(");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("§3 — CSPRNG guard")
    class CsprngGuard {

        @Test
        @DisplayName("should_useSecureRandom_when_generatingOtp")
        void should_useSecureRandom_when_generatingOtp() throws IOException {
            String source = Files.readString(AUTH_SRC.resolve("SecureTokenGenerator.java"));

            assertThat(source)
                    .as("SecureTokenGenerator must use SecureRandom for OTP generation")
                    .contains("SecureRandom");

            assertThat(source)
                    .as("Math.random() is a predictable PRNG — forbidden in OTP generation")
                    .doesNotContain("Math.random()");

            assertThat(source)
                    .as("new Random() is a predictable PRNG — forbidden in OTP generation")
                    .doesNotContain("new Random(");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("§4 — Log hygiene guard")
    class LogHygieneGuard {

        @Test
        @DisplayName("should_neverLogOtp_when_verificationFlowRuns")
        void should_neverLogOtp_when_verificationFlowRuns() throws IOException {
            List<String> violations;
            try (Stream<Path> authFiles  = Files.walk(AUTH_SRC);
                 Stream<Path> notifFiles = Files.walk(NOTIF_SRC)) {

                violations = Stream.concat(authFiles, notifFiles)
                        .filter(p -> p.toString().endsWith(".java"))
                        .flatMap(file -> {
                            String src;
                            try {
                                src = Files.readString(file);
                            } catch (IOException e) {
                                throw new RuntimeException("Failed to read " + file, e);
                            }
                            String[] lines = src.split("\n");
                            List<String> hits = new java.util.ArrayList<>();
                            for (int i = 0; i < lines.length; i++) {
                                String line = lines[i];
                                // A log call that references the OTP variable by its canonical name.
                                // "rawOtp" is the local variable holding the plaintext OTP in AuthService.
                                if (line.contains("log.") && line.contains("rawOtp")) {
                                    hits.add(file.getFileName() + ":" + (i + 1) + ": " + line.trim());
                                }
                            }
                            return hits.stream();
                        })
                        .collect(Collectors.toList());
            }

            assertThat(violations)
                    .as("No source file in auth/ or notification/ may pass the OTP value (rawOtp) to any log statement")
                    .isEmpty();
        }
    }

    // ──────────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("§4b — Broadened PII/secret log-hygiene guard (auth + notification + common.exception)")
    class BroadenedLogHygieneGuard {

        // A log line that references any of these tokens is a PII/secret leak.
        // ".getEmail(" — email address PII (the GlobalExceptionHandler regression).
        // "rawOtp" / ".code()" / "request.code()" — the plaintext OTP.
        private static final String[] FORBIDDEN_ON_LOG_LINE = {
                ".getEmail(", "rawOtp", "request.code()", ".code()"
        };

        @Test
        @DisplayName("should_neverLogEmailOrCode_when_scanningAuthNotificationAndExceptionTrees")
        void should_neverLogEmailOrCode_when_scanningAuthNotificationAndExceptionTrees() throws IOException {
            List<String> violations;
            try (Stream<Path> authFiles  = Files.walk(AUTH_SRC);
                 Stream<Path> notifFiles = Files.walk(NOTIF_SRC);
                 Stream<Path> excFiles   = Files.walk(EXC_SRC)) {

                violations = Stream.of(authFiles, notifFiles, excFiles)
                        .flatMap(s -> s)
                        .filter(p -> p.toString().endsWith(".java"))
                        .flatMap(file -> {
                            String src;
                            try {
                                src = Files.readString(file);
                            } catch (IOException e) {
                                throw new RuntimeException("Failed to read " + file, e);
                            }
                            String[] lines = src.split("\n");
                            List<String> hits = new java.util.ArrayList<>();
                            for (int i = 0; i < lines.length; i++) {
                                String line = lines[i];
                                if (!line.contains("log.")) {
                                    continue;
                                }
                                for (String forbidden : FORBIDDEN_ON_LOG_LINE) {
                                    if (line.contains(forbidden)) {
                                        hits.add(file.getFileName() + ":" + (i + 1)
                                                + " [" + forbidden + "] " + line.trim());
                                    }
                                }
                            }
                            return hits.stream();
                        })
                        .collect(Collectors.toList());
            }

            assertThat(violations)
                    .as("No log statement in auth/, notification/ or common.exception/ "
                            + "may reference an email address (.getEmail()) or the OTP "
                            + "(rawOtp / request.code() / .code())")
                    .isEmpty();
        }
    }
}
