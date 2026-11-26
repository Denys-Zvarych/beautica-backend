package com.beautica.media.service;

/**
 * A veto {@link AfterCommitBlobPurger} consults, at purge time, before deleting a key: for storage whose keys are
 * content-addressed (the same bytes always map to the same key), a later transaction can re-reference a key an
 * earlier one dereferenced, so "dereferenced when the purge was queued" is not "unreferenced when it runs".
 *
 * <p>Implementations run on {@code blobPurgeExecutor}, outside any transaction, and must read committed state.
 */
public interface BlobReferenceGuard {

    /** {@code true} when this guard owns {@code key} (e.g. by key prefix). */
    boolean appliesTo(String key);

    /** {@code true} when a committed row still references {@code key}; the purge then keeps the object. */
    boolean isReferenced(String key);

    /**
     * Runs a purge's reference check AND its delete as one step relative to this guard's writers (e.g. under a lock a
     * writer takes before re-creating a key), so a key claimed after the check is never deleted by it. Must run
     * {@code checkAndDelete} exactly once. Default: no exclusion.
     */
    default void exclusively(Runnable checkAndDelete) {
        checkAndDelete.run();
    }
}
