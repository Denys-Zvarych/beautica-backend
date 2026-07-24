package com.beautica.media.service;

import com.beautica.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.util.Optional;

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

    private static String stripTrailingSlash(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
