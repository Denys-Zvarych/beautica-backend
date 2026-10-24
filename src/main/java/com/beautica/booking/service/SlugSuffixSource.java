package com.beautica.booking.service;

/**
 * Phase 378 — the random part of a public booking slug ({@code base + "-" + suffix}), drawn once per
 * generator attempt. A seam only: production uses {@link SecureRandomSlugSuffixSource} (4 chars of
 * {@code [a-z0-9]} from {@link java.security.SecureRandom}, byte-identical to the generator before the
 * seam); tests inject a deterministic sequence to force collisions with live or retired slugs.
 */
@FunctionalInterface
public interface SlugSuffixSource {

    /** One fresh suffix, matching {@code [a-z0-9]{4}}. */
    String nextSuffix();
}
