package com.beautica.media.service;

import com.beautica.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link R2StorageService}. Pure JUnit 5 + Mockito — no Spring context.
 *
 * <p>Construction strategy mirrors {@code EmailServiceTest}: instantiate the service
 * directly with explicit constructor arguments so we can exercise both enabled
 * ({@code Optional.of(s3Client)}) and disabled ({@code Optional.empty()}) modes
 * from the same test class.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("R2StorageService — unit")
class R2StorageServiceTest {

    private static final String BUCKET = "beautica-media";
    private static final String PUBLIC_URL = "https://pub.example.r2.dev";
    private static final String PUBLIC_URL_TRAILING = "https://pub.example.r2.dev/";
    private static final String KEY = "uuid-key";
    private static final String CONTENT_TYPE = "image/jpeg";
    private static final byte[] PAYLOAD = "hello".getBytes();

    @Mock
    private S3Client s3Client;

    @Test
    @DisplayName("uploadFile returns early without S3 interaction when R2 is disabled")
    void should_returnEarly_when_uploadFileCalledWithR2Disabled() {
        R2StorageService service = new R2StorageService(Optional.empty(), BUCKET, PUBLIC_URL);
        InputStream content = new ByteArrayInputStream(PAYLOAD);

        service.uploadFile(KEY, content, PAYLOAD.length, CONTENT_TYPE);

        verifyNoInteractions(s3Client);
    }

    @Test
    @DisplayName("uploadFile calls putObject with correct bucket/key/contentType/contentLength when enabled")
    void should_callPutObject_when_uploadFileCalledWithR2Enabled() {
        R2StorageService service = new R2StorageService(Optional.of(s3Client), BUCKET, PUBLIC_URL);
        InputStream content = new ByteArrayInputStream(PAYLOAD);
        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);

        service.uploadFile(KEY, content, PAYLOAD.length, CONTENT_TYPE);

        verify(s3Client).putObject(requestCaptor.capture(), any(RequestBody.class));
        PutObjectRequest captured = requestCaptor.getValue();
        assertThat(captured.bucket()).isEqualTo(BUCKET);
        assertThat(captured.key()).isEqualTo(KEY);
        assertThat(captured.contentType()).isEqualTo(CONTENT_TYPE);
        assertThat(captured.contentLength()).isEqualTo((long) PAYLOAD.length);
    }

    @Test
    @DisplayName("deleteFile returns early without S3 interaction when R2 is disabled")
    void should_returnEarly_when_deleteFileCalledWithR2Disabled() {
        R2StorageService service = new R2StorageService(Optional.empty(), BUCKET, PUBLIC_URL);

        service.deleteFile(KEY);

        verifyNoInteractions(s3Client);
    }

    @Test
    @DisplayName("deleteFile calls deleteObject with correct bucket/key when enabled")
    void should_callDeleteObject_when_deleteFileCalledWithR2Enabled() {
        R2StorageService service = new R2StorageService(Optional.of(s3Client), BUCKET, PUBLIC_URL);
        ArgumentCaptor<DeleteObjectRequest> requestCaptor = ArgumentCaptor.forClass(DeleteObjectRequest.class);

        service.deleteFile(KEY);

        verify(s3Client).deleteObject(requestCaptor.capture());
        DeleteObjectRequest captured = requestCaptor.getValue();
        assertThat(captured.bucket()).isEqualTo(BUCKET);
        assertThat(captured.key()).isEqualTo(KEY);
    }

    @Test
    @DisplayName("buildPublicUrl returns empty string when R2 is disabled")
    void should_returnEmptyString_when_buildPublicUrlCalledWithR2Disabled() {
        R2StorageService service = new R2StorageService(Optional.empty(), BUCKET, PUBLIC_URL);

        String url = service.buildPublicUrl(KEY);

        assertThat(url).isEmpty();
    }

    @Test
    @DisplayName("buildPublicUrl concatenates configured public URL prefix and key when enabled")
    void should_concatenatePublicUrlAndKey_when_buildPublicUrlCalledWithR2Enabled() {
        R2StorageService service = new R2StorageService(Optional.of(s3Client), BUCKET, PUBLIC_URL);

        String url = service.buildPublicUrl(KEY);

        assertThat(url).isEqualTo("https://pub.example.r2.dev/uuid-key");
    }

    @Test
    @DisplayName("buildPublicUrl strips trailing slash so concatenation never produces double slash")
    void should_stripTrailingSlash_when_publicUrlHasTrailingSlash() {
        R2StorageService service = new R2StorageService(Optional.of(s3Client), BUCKET, PUBLIC_URL_TRAILING);

        String url = service.buildPublicUrl(KEY);

        assertThat(url)
                .as("trailing slash on public-url must not cascade into the final URL")
                .isEqualTo("https://pub.example.r2.dev/uuid-key")
                .doesNotContain("//uuid-key");
    }

    @Test
    @DisplayName("uploadFile translates S3Exception to BusinessException (502) without leaking SDK details")
    void should_translateS3Exception_when_uploadFileFails() {
        R2StorageService service = new R2StorageService(Optional.of(s3Client), BUCKET, PUBLIC_URL);
        InputStream content = new ByteArrayInputStream(PAYLOAD);
        S3Exception sdkException = (S3Exception) S3Exception.builder().message("fail").build();
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(sdkException);

        assertThatThrownBy(() -> service.uploadFile(KEY, content, PAYLOAD.length, CONTENT_TYPE))
                .isInstanceOf(BusinessException.class)
                .hasMessageNotContainingAny("fail", "S3Exception")
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(HttpStatus.BAD_GATEWAY);

        // Sanity: the failing call was attempted exactly once, never silently swallowed.
        verify(s3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    @DisplayName("uploadFile translates SdkClientException to BusinessException (502) without leaking SDK details")
    void should_translateSdkClientException_when_uploadFileFails() {
        R2StorageService service = new R2StorageService(Optional.of(s3Client), BUCKET, PUBLIC_URL);
        InputStream content = new ByteArrayInputStream(PAYLOAD);
        SdkClientException sdkException = SdkClientException.builder().message("network down").build();
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(sdkException);

        assertThatThrownBy(() -> service.uploadFile(KEY, content, PAYLOAD.length, CONTENT_TYPE))
                .isInstanceOf(BusinessException.class)
                .hasMessageNotContainingAny("network down", "SdkClientException")
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(HttpStatus.BAD_GATEWAY);

        // Sanity: the failing call was attempted exactly once, never silently swallowed.
        verify(s3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    @DisplayName("deleteFile translates S3Exception to BusinessException (502) without leaking SDK details")
    void should_translateS3Exception_when_deleteFileFails() {
        R2StorageService service = new R2StorageService(Optional.of(s3Client), BUCKET, PUBLIC_URL);
        S3Exception sdkException = (S3Exception) S3Exception.builder().message("fail").build();
        when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                .thenThrow(sdkException);

        assertThatThrownBy(() -> service.deleteFile(KEY))
                .isInstanceOf(BusinessException.class)
                .hasMessageNotContainingAny("fail", "S3Exception")
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(HttpStatus.BAD_GATEWAY);

        // Sanity: the failing call was attempted exactly once, never silently swallowed.
        verify(s3Client).deleteObject(any(DeleteObjectRequest.class));
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    @DisplayName("deleteFile translates SdkClientException to BusinessException (502) without leaking SDK details")
    void should_translateSdkClientException_when_deleteFileFails() {
        R2StorageService service = new R2StorageService(Optional.of(s3Client), BUCKET, PUBLIC_URL);
        SdkClientException sdkException = SdkClientException.builder().message("network down").build();
        when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                .thenThrow(sdkException);

        assertThatThrownBy(() -> service.deleteFile(KEY))
                .isInstanceOf(BusinessException.class)
                .hasMessageNotContainingAny("network down", "SdkClientException")
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(HttpStatus.BAD_GATEWAY);

        // Sanity: the failing call was attempted exactly once, never silently swallowed.
        verify(s3Client).deleteObject(any(DeleteObjectRequest.class));
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }
}
