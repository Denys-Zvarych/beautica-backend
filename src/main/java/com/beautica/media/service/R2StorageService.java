package com.beautica.media.service;

import com.beautica.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Error;
import software.amazon.awssdk.services.s3.model.S3Exception;

import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Thin wrapper around the Cloudflare R2 {@link S3Client} for media uploads.
 *
 * <p><b>Activation:</b> {@link com.beautica.config.S3Config} registers the {@link S3Client}
 * bean only when {@code app.cloudflare-r2.enabled=true}. This service injects
 * {@code Optional<S3Client>} so it can be instantiated in either mode without
 * {@code @Autowired(required=false)} (Spring's recommended replacement is optional
 * injection at the constructor).
 *
 * <p><b>Disabled mode (no-op stub):</b> when the bean is absent (local/test/CI without
 * R2 credentials), every public method short-circuits with a WARN log so the app can
 * still boot and unrelated features still run. The startup log line records which mode
 * is active so operators can confirm intent from the boot log without scanning
 * property files — mirrors {@code FirebaseConfig}'s no-op-bean discipline.
 *
 * <p><b>Exception translation:</b> raw SDK exceptions ({@link S3Exception},
 * {@link SdkClientException}) are translated into {@link BusinessException} with HTTP
 * 502 ({@code BAD_GATEWAY}) so the global error handler returns a clean structured
 * response. The exception cause carries the original SDK error for log inspection;
 * the user-visible message never echoes SDK internals.
 */
@Slf4j
@Service
public class R2StorageService {

    private static final String UPLOAD_FAILED_MESSAGE = "Failed to upload file to media storage";
    private static final String DELETE_FAILED_MESSAGE = "Failed to delete file from media storage";
    private static final String DISABLED_SUFFIX = " — R2 is disabled; no-op";

    /** S3 {@code DeleteObjects} accepts at most this many keys per request. */
    private static final int MAX_BATCH_DELETE_KEYS = 1000;

    private final Optional<S3Client> s3Client;
    private final String bucketName;
    private final String publicUrlPrefix;
    private final boolean r2Enabled;

    /**
     * @param s3Client  optional {@link S3Client}; {@link Optional#empty()} when
     *                  {@code app.cloudflare-r2.enabled=false}
     * @param bucketName R2 bucket name; may be blank in disabled mode
     * @param publicUrl  base public URL for the bucket (e.g. {@code https://pub.<hash>.r2.dev});
     *                   may be blank in disabled mode; trailing slash is stripped defensively
     *                   to prevent {@code https://pub.x.r2.dev//uuid} double-slash URLs
     */
    public R2StorageService(
            Optional<S3Client> s3Client,
            @Value("${app.cloudflare-r2.bucket:}") String bucketName,
            @Value("${app.cloudflare-r2.public-url:}") String publicUrl
    ) {
        this.s3Client = s3Client;
        this.bucketName = bucketName;
        this.publicUrlPrefix = stripTrailingSlash(publicUrl);
        this.r2Enabled = s3Client.isPresent();

        if (r2Enabled) {
            if (!StringUtils.hasText(bucketName)) {
                throw new IllegalStateException("R2 is enabled but app.cloudflare-r2.bucket-name is blank");
            }
            if (!StringUtils.hasText(publicUrlPrefix)) {
                throw new IllegalStateException("R2 is enabled but app.cloudflare-r2.public-url is blank");
            }
            log.info("R2StorageService enabled — uploads will be sent to Cloudflare R2");
        } else {
            log.warn("R2StorageService disabled — uploadFile/deleteFile are no-ops, "
                    + "buildPublicUrl returns empty string");
        }
    }

    /**
     * Uploads an object to the configured R2 bucket.
     *
     * <p><b>Disabled mode:</b> logs WARN and returns immediately without touching
     * the input stream or throwing — callers must treat a successful return as
     * "best effort, may have been suppressed". This keeps local/test profiles
     * runnable without R2 credentials.
     *
     * @param key            R2 object key (server-generated UUID — caller's responsibility)
     * @param content        upload payload stream (caller owns the stream lifecycle)
     * @param contentLength  payload size in bytes; must match the stream exactly
     * @param contentType    MIME type detected by the caller (never the raw client header)
     * @throws BusinessException when the R2 request fails (HTTP 502); cause carries
     *                           the original {@link S3Exception} / {@link SdkClientException}
     */
    public void uploadFile(String key, InputStream content, long contentLength, String contentType) {
        if (!r2Enabled) {
            // Key encodes the user UUID — omit from WARN log to avoid PII in log aggregators.
            log.warn("uploadFile(key=[key omitted], size={}B){}", contentLength, DISABLED_SUFFIX);
            return;
        }

        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .contentType(contentType)
                .contentLength(contentLength)
                .build();

        try {
            s3Client.get().putObject(request, RequestBody.fromInputStream(content, contentLength));
        } catch (S3Exception | SdkClientException ex) {
            // Key encodes the user UUID — omit from ERROR log to avoid PII in log aggregators.
            log.error("R2 upload failed for key=[key omitted]: {}", ex.getClass().getSimpleName());
            throw new BusinessException(HttpStatus.BAD_GATEWAY, UPLOAD_FAILED_MESSAGE);
        }
    }

    /**
     * Deletes an object from the configured R2 bucket by its stored key.
     *
     * <p><b>Disabled mode:</b> logs WARN and returns immediately. Callers should
     * still issue the delete request so production behaviour is unchanged when
     * the feature flag flips.
     *
     * @param key R2 object key as stored in {@code media_files.r2_key}
     * @throws BusinessException when the R2 request fails (HTTP 502); cause carries
     *                           the original SDK exception
     */
    public void deleteFile(String key) {
        if (!r2Enabled) {
            // Key encodes the user UUID — omit from WARN log to avoid PII in log aggregators.
            log.warn("deleteFile(key=[key omitted]){}", DISABLED_SUFFIX);
            return;
        }

        DeleteObjectRequest request = DeleteObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();

        try {
            s3Client.get().deleteObject(request);
        } catch (S3Exception | SdkClientException ex) {
            // Key encodes the user UUID — omit from ERROR log to avoid PII in log aggregators.
            log.error("R2 delete failed for key=[key omitted]: {}", ex.getClass().getSimpleName());
            throw new BusinessException(HttpStatus.BAD_GATEWAY, DELETE_FAILED_MESSAGE);
        }
    }

    /**
     * Batch-deletes multiple objects from the configured R2 bucket in as few HTTP round-trips
     * as S3's {@code DeleteObjects} API allows — a single request accepts up to {@value
     * #MAX_BATCH_DELETE_KEYS} keys, so a larger input is chunked at that limit.
     *
     * <p><b>Backs {@code MediaService#sweepBlobs}</b> (Phase 268 perf follow-up). The sweep
     * previously called {@link #deleteFile(String)} once per key — a salon with dozens of
     * portfolio photos meant dozens of sequential R2 round-trips inside a teardown sweep
     * that runs synchronously after the deletion transaction commits.
     *
     * <p><b>Never throws for a delete failure, partial or total</b> — this is the load-bearing
     * contract difference from {@link #deleteFile(String)}. {@code DeleteObjects} reports a
     * per-key failure in its response body (an {@code Errors} entry), not as an exception, so
     * those keys are simply collected into the returned set. A whole-chunk transport failure
     * ({@link S3Exception} / {@link SdkClientException} — e.g. the request itself never
     * reached R2) is likewise absorbed here and every key in that chunk is reported back as
     * failed, so a caller that already treats "key present in the returned set" as "log and
     * move on, drop the DB pointer regardless" needs no separate handling for a transport-level
     * failure versus a reported per-key one.
     *
     * <p><b>Disabled mode:</b> logs WARN (key count only, no keys) and returns an empty set,
     * mirroring {@link #deleteFile(String)}'s no-op contract.
     *
     * @param keys R2 object keys to delete; duplicates are removed before chunking, order is
     *             not preserved in the returned set, an empty input is a no-op
     * @return the subset of {@code keys} that could not be deleted (never {@code null}); empty
     *         when every key was deleted or R2 is disabled
     */
    public Set<String> deleteFiles(Collection<String> keys) {
        if (keys.isEmpty()) {
            return Set.of();
        }
        if (!r2Enabled) {
            // Keys may encode entity UUIDs — omit from WARN log to avoid PII in log aggregators.
            log.warn("deleteFiles(count={}){}", keys.size(), DISABLED_SUFFIX);
            return Set.of();
        }

        List<String> distinctKeys = keys.stream().distinct().toList();
        Set<String> failedKeys = new HashSet<>();
        for (int i = 0; i < distinctKeys.size(); i += MAX_BATCH_DELETE_KEYS) {
            List<String> chunk = distinctKeys.subList(i, Math.min(i + MAX_BATCH_DELETE_KEYS, distinctKeys.size()));
            failedKeys.addAll(deleteChunk(chunk));
        }
        return failedKeys;
    }

    /** Deletes a single chunk (at most {@value #MAX_BATCH_DELETE_KEYS} keys) via one {@code DeleteObjects} call. */
    private Set<String> deleteChunk(List<String> chunk) {
        List<ObjectIdentifier> objectIds = new ArrayList<>(chunk.size());
        for (String key : chunk) {
            objectIds.add(ObjectIdentifier.builder().key(key).build());
        }
        DeleteObjectsRequest request = DeleteObjectsRequest.builder()
                .bucket(bucketName)
                .delete(Delete.builder().objects(objectIds).quiet(false).build())
                .build();

        try {
            DeleteObjectsResponse response = s3Client.get().deleteObjects(request);
            if (response.errors() == null || response.errors().isEmpty()) {
                return Set.of();
            }
            log.warn("R2 batch delete reported {} failed key(s) of {} requested",
                    response.errors().size(), chunk.size());
            return response.errors().stream()
                    .map(S3Error::key)
                    .collect(Collectors.toSet());
        } catch (S3Exception | SdkClientException ex) {
            // Chunk keys may encode entity UUIDs — omit from ERROR log to avoid PII in log aggregators.
            log.error("R2 batch delete failed for {} key(s): {}", chunk.size(), ex.getClass().getSimpleName());
            return new HashSet<>(chunk);
        }
    }

    /**
     * Builds a public read URL for the given R2 key.
     *
     * <p><b>Disabled mode:</b> returns an empty string so callers can safely concat
     * without NPE. The empty value is propagated through the {@code r2_url} column
     * unchanged — the caller (Phase 7.5 {@code MediaService}) decides whether an
     * empty URL is an acceptable response in disabled profiles.
     *
     * <p>The constructor strips any trailing slash from the configured {@code public-url}
     * so concatenation never yields {@code https://pub.x.r2.dev//uuid}.
     *
     * @param key R2 object key (server-generated UUID)
     * @return public URL in the form {@code <public-url>/<key>}, or empty string when disabled
     */
    public String buildPublicUrl(String key) {
        if (!r2Enabled) {
            // Key encodes the user UUID — omit from WARN log to avoid PII in log aggregators.
            log.warn("buildPublicUrl(key=[key omitted]){}", DISABLED_SUFFIX);
            return "";
        }
        return publicUrlPrefix + "/" + key;
    }

    /**
     * Inverse of {@link #buildPublicUrl}: recovers the R2 key from a previously-built public URL,
     * or {@link Optional#empty()} when {@code url} cannot possibly be ours.
     *
     * <p>Backs the Phase 268 D2 salon-imagery sweep, where {@code salons.avatar_url} /
     * {@code cover_image_url} store only the public URL (unlike {@code users.avatar_r2_key}, which
     * stores the raw key directly) — the key must be recovered before it can be handed to
     * {@link #deleteFile(String)}.
     *
     * <p><b>Guards, in this exact order — never reordered, never guessed at:</b>
     * <ol>
     *   <li>R2 disabled → empty. No configured prefix to compare against, and nothing to delete
     *       anyway.</li>
     *   <li>{@code publicUrlPrefix} blank → empty. <b>Critical</b>: {@code "".startsWith("")} and
     *       every string's {@code startsWith("/…")} check would otherwise let a blank prefix match
     *       ANY url and misroute its full path as a "key".</li>
     *   <li>{@code url} does not start with {@code publicUrlPrefix + "/"} → empty. A URL that is
     *       not ours (a foreign host, a malformed value, or an attacker-supplied string) must never
     *       be turned into a delete against a guessed key.</li>
     *   <li>The remainder after stripping the prefix is blank → empty. A bare prefix with nothing
     *       after it is not a key.</li>
     * </ol>
     *
     * @param url a previously-stored public URL, possibly {@code null}, blank, foreign, or
     *            malformed
     * @return the recovered R2 key, or empty when {@code url} does not carry the configured prefix
     */
    public Optional<String> extractKeyFromPublicUrl(String url) {
        if (!r2Enabled) {
            return Optional.empty();
        }
        if (!StringUtils.hasText(publicUrlPrefix)) {
            return Optional.empty();
        }
        if (url == null || !url.startsWith(publicUrlPrefix + "/")) {
            return Optional.empty();
        }
        String key = url.substring(publicUrlPrefix.length() + 1);
        return StringUtils.hasText(key) ? Optional.of(key) : Optional.empty();
    }

    private static String stripTrailingSlash(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
