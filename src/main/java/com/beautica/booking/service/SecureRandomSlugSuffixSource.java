package com.beautica.booking.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Production {@link SlugSuffixSource}: {@value #LENGTH} characters of {@code [a-z0-9]} from one shared
 * {@link SecureRandom} (thread-safe). Moved verbatim out of {@code BookingSlugService} (phase 378).
 */
@Component
public class SecureRandomSlugSuffixSource implements SlugSuffixSource {

    static final int LENGTH = 4;
    private static final char[] ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    private final SecureRandom random = new SecureRandom();

    @Override
    public String nextSuffix() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
