package com.beautica.salon.entity;

/**
 * One salon image slot's stored pointers: the public {@code url} (the read model) and the R2 {@code key}
 * (Phase 343, V189). A legacy row carries only the URL; an empty slot carries neither.
 */
public record SalonImagePointer(String url, String key) {

    public static final SalonImagePointer EMPTY = new SalonImagePointer(null, null);

    public boolean isEmpty() {
        return url == null && key == null;
    }
}
