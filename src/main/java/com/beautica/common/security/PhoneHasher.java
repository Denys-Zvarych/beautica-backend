package com.beautica.common.security;

import com.beautica.common.exception.BusinessException;
import com.beautica.common.util.UkrainianPhoneNormalizer;
import com.beautica.config.PhoneHashPepperConfig;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Optional;

/**
 * Phase 397 D3 — the one stable pseudonymous phone key: {@code HMAC-SHA256(toE164(raw), pepper)}.
 *
 * <p>Every spelling of the same Ukrainian number ({@code +380…}, {@code 0…}, spaced) is normalised by
 * {@link UkrainianPhoneNormalizer} first, so they hash equal. The pepper is dedicated
 * ({@link PhoneHashPepperConfig}) and used raw as the HMAC key (UTF-8), like the OTP pepper. Guest reviews
 * (phase 399 {@code sms_opt_outs}) reuse this component and pepper.
 *
 * <p>A {@link Mac} is not thread-safe, so one is created per call from the immutable key spec.
 * Neither the raw phone nor the digest is ever logged.
 */
@Component
public class PhoneHasher {

    private static final String ALGORITHM = "HmacSHA256";
    private static final int LAST_DIGITS = 4;

    private final SecretKeySpec key;

    public PhoneHasher(PhoneHashPepperConfig config) {
        this.key = new SecretKeySpec(config.phoneHashPepper().getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    /**
     * A hashed phone and its last four digits (for the masked display, D4). The raw number is not kept.
     *
     * <p>A final class with a private constructor, not a record (398 security LOW): a record's canonical
     * constructor is always public, so any caller could mint a {@code HashedPhone} around a raw or foreign digest.
     * Only {@link PhoneHasher#hash} / {@link PhoneHasher#tryHash} can create one, which is what makes the typed
     * {@code ClientBlockService#isGuestPhoneBlocked(BlockScope, HashedPhone)} parameter meaningful.
     */
    public static final class HashedPhone {

        private final byte[] hash;
        private final String last4;

        private HashedPhone(byte[] hash, String last4) {
            this.hash = hash.clone();
            this.last4 = last4;
        }

        /** 32-byte HMAC-SHA256 digest (defensive copy). */
        public byte[] hash() {
            return hash.clone();
        }

        /** The last four digits of the E.164 form. */
        public String last4() {
            return last4;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof HashedPhone other && Arrays.equals(hash, other.hash) && last4.equals(other.last4);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(hash) + last4.hashCode();
        }

        @Override
        public String toString() {
            return "HashedPhone[<redacted>]";
        }
    }

    /**
     * @throws BusinessException 400 "Invalid phone format" when the number is not a Ukrainian phone
     */
    public HashedPhone hash(String rawPhone) {
        String e164 = UkrainianPhoneNormalizer.toE164(rawPhone);
        return new HashedPhone(hmac(e164), e164.substring(e164.length() - LAST_DIGITS));
    }

    /** Lenient form for stored phones of unknown quality (e.g. a profile phone): empty instead of a 400. */
    public Optional<HashedPhone> tryHash(String rawPhone) {
        if (rawPhone == null || rawPhone.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(hash(rawPhone));
        } catch (BusinessException e) {
            return Optional.empty();
        }
    }

    private byte[] hmac(String e164) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(e164.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
