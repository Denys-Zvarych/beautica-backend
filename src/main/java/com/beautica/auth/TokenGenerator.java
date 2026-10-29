package com.beautica.auth;

public interface TokenGenerator {

    String generateToken();

    /**
     * A random token of {@code byteLength} bytes from the same CSPRNG as {@link #generateToken()}, base64url without
     * padding. For short-lived capabilities whose carrier has a hard length budget (phase 400: a 16-byte review-invite
     * token = 22 chars, so the SMS stays one UCS-2 segment).
     *
     * @throws IllegalArgumentException when {@code byteLength < 16} (below 128 bits of entropy)
     */
    String generateToken(int byteLength);

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
