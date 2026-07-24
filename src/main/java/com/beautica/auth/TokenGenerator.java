package com.beautica.auth;

public interface TokenGenerator {

    String generateToken();

    String hash(String rawToken);

    /**
     * Keyed HMAC-SHA256 hash of a 6-digit OTP using a server-side pepper.
     *
     * <p>Distinct from {@link #hash(String)} (a bare unkeyed digest, acceptable
     * only for 256-bit refresh tokens). A 6-digit OTP under a bare digest is
     * brute-forced offline in milliseconds from any hash disclosure — the pepper
     * makes a leaked digest useless without the server secret.
     *
     * @return a 64-char lowercase hex digest (compatible with the
     *         {@code chk_verification_code_hash_format} DB constraint)
     */
    String hashOtp(String rawOtp);

    String generateOtp();
}
