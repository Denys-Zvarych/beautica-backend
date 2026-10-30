package com.beautica.notification.telegram.link;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Phase 409 audit-fix cycle 2 — the open-phone set of the orphan backstop as a sorted {@code long[]} of the first
 * 8 bytes of each phone HMAC: 8 bytes per phone (≈ 8 MB at the 1M-phone budget) instead of a {@code HashSet} of
 * buffers (≈ 150 MB). A prefix collision can only make {@link #contains} answer {@code true} for a phone without a
 * booking — the link is spared, never wrongly deleted.
 */
public final class PhoneHashPrefixes {

    private static final int INITIAL_CAPACITY = 1_024;

    private long[] prefixes = new long[INITIAL_CAPACITY];
    private int size;
    private boolean sorted;

    /** Adds the prefix of one 32-byte phone hash; not allowed after the first {@link #contains}. */
    public void add(byte[] phoneHash) {
        if (sorted) {
            throw new IllegalStateException("PhoneHashPrefixes is sealed");
        }
        if (size == prefixes.length) {
            prefixes = Arrays.copyOf(prefixes, size * 2);
        }
        prefixes[size++] = prefix(phoneHash);
    }

    /** {@code true} when a phone with this hash's 8-byte prefix was added. Seals the set on first call. */
    public boolean contains(byte[] phoneHash) {
        seal();
        return Arrays.binarySearch(prefixes, 0, size, prefix(phoneHash)) >= 0;
    }

    public int size() {
        return size;
    }

    /** Heap held by the prefix array (pinned by {@code PhoneHashPrefixesTest}). */
    public long retainedBytes() {
        return (long) prefixes.length * Long.BYTES;
    }

    private void seal() {
        if (!sorted) {
            prefixes = Arrays.copyOf(prefixes, size);
            Arrays.sort(prefixes);
            sorted = true;
        }
    }

    private static long prefix(byte[] phoneHash) {
        return ByteBuffer.wrap(phoneHash, 0, Long.BYTES).getLong();
    }
}
