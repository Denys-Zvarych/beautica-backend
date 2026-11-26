package com.beautica.catalog.icon;

import com.beautica.media.service.BlobReferenceGuard;
import com.beautica.service.repository.PlatformCategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * B539 — a category icon key is content-addressed ({@code catalog/category-icons/{name}/{sha16}.svg}), so a later
 * write can re-reference a key an earlier purge was queued for: A→B→A replace, a rolled-back upload of an icon a
 * concurrent write committed, or a delete + re-create with the same SVG. A purge of such a key therefore keeps the
 * object while a committed {@code platform_categories.icon_key} row OR an in-flight write points at it.
 *
 * <p><b>In-flight claims.</b> {@link CategoryIconService} {@link #claim claims} the key BEFORE its R2 PUT and
 * {@link #release releases} it only once its write transaction has ended, so a purge can never see "no committed row"
 * for an object an uncommitted write is about to reference. The purge's check-then-delete runs under the write lock
 * ({@link #exclusively}); a claim takes the read lock, so it waits out a purge already past its check and its PUT then
 * re-creates the object. No R2 I/O or row lock is involved in a claim. A claim count (not a set) keeps concurrent
 * writers of the same key independent.
 *
 * <p><b>Scope.</b> Claims live in this JVM: correct for a single backend instance (the Railway deployment); a second
 * instance would reopen the window across instances. Claims are never persisted — a restart drops them together with
 * the uploads they covered, and a purge lost on shutdown only leaves an orphan, never deletes a live object.
 */
@Component
@RequiredArgsConstructor
public class CategoryIconReferenceGuard implements BlobReferenceGuard {

    private final PlatformCategoryRepository platformCategoryRepository;
    private final ConcurrentHashMap<String, Integer> inFlight = new ConcurrentHashMap<>();
    private final ReadWriteLock purgeLock = new ReentrantReadWriteLock();

    @Override
    public boolean appliesTo(String key) {
        return key != null && key.startsWith(CategoryIconService.KEY_PREFIX);
    }

    /** Claimed keys first: a write that releases after this read has committed, so the DB read below sees it. */
    @Override
    public boolean isReferenced(String key) {
        return inFlight.containsKey(key) || platformCategoryRepository.existsByIconKey(key);
    }

    @Override
    public void exclusively(Runnable checkAndDelete) {
        purgeLock.writeLock().lock();
        try {
            checkAndDelete.run();
        } finally {
            purgeLock.writeLock().unlock();
        }
    }

    /** Marks {@code key} as about to be uploaded and referenced; call BEFORE the PUT, pair with {@link #release}. */
    public void claim(String key) {
        purgeLock.readLock().lock();
        try {
            inFlight.merge(key, 1, Integer::sum);
        } finally {
            purgeLock.readLock().unlock();
        }
    }

    /** Ends one claim of {@code key}; call only after the claiming write's transaction has completed. */
    public void release(String key) {
        inFlight.computeIfPresent(key, (k, count) -> count == 1 ? null : count - 1);
    }
}
