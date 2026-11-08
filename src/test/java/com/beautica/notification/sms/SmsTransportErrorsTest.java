package com.beautica.notification.sms;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.client.ResourceAccessException;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CancellationException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SmsTransportErrors — pre-send vs ambiguous transport failures (phase 439 extraction)")
class SmsTransportErrorsTest {

    static Stream<Throwable> preSendCauses() {
        return Stream.of(new ConnectException("refused"), new HttpConnectTimeoutException("connect timeout"),
                new UnknownHostException("nope"), new NoRouteToHostException("no route"),
                new SSLHandshakeException("handshake"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("preSendCauses")
    @DisplayName("a failure before the request is written (refused, connect timeout, unknown host, no route, TLS handshake) is pre-send and never ambiguous")
    void should_classifyAsPreSend_when_failureHappensBeforeTheWrite(Throwable cause) {
        ResourceAccessException wrapped = new ResourceAccessException("I/O error", new IOException("wrap", cause));

        boolean preSend = SmsTransportErrors.isPreSend(cause);
        boolean unknown = SmsTransportErrors.isOutcomeUnknown(wrapped);

        assertThat(preSend).isTrue();
        assertThat(unknown).as("a pre-send cause anywhere in the chain is a definite failure").isFalse();
    }

    @Test
    @DisplayName("a read timeout after the write is ambiguous: the provider may have sent the SMS")
    void should_classifyAsAmbiguous_when_readTimesOutAfterTheWrite() {
        HttpTimeoutException readTimeout = new HttpTimeoutException("request timed out");
        ResourceAccessException wrapped = new ResourceAccessException("I/O error", readTimeout);

        boolean preSend = SmsTransportErrors.isPreSend(readTimeout);
        boolean unknown = SmsTransportErrors.isOutcomeUnknown(wrapped);

        assertThat(preSend).isFalse();
        assertThat(unknown).isTrue();
    }

    @Test
    @DisplayName("a non-I/O failure is not ambiguous, even with a socket timeout buried in its cause")
    void should_notBeAmbiguous_when_failureIsNotAnIoFailure() {
        IllegalStateException serialization = new IllegalStateException("x", new SocketTimeoutException());

        boolean unknown = SmsTransportErrors.isOutcomeUnknown(serialization);

        assertThat(unknown).isFalse();
    }

    @Test
    @DisplayName("TurbosmsService and the extracted helper share the same cause-chain depth cap of 16")
    void should_keepTurbosmsDepthCapAligned_when_extracted() {
        int turbosmsCap = TurbosmsService.MAX_CAUSE_DEPTH;

        assertThat(turbosmsCap).isEqualTo(SmsTransportErrors.MAX_CAUSE_DEPTH).isEqualTo(16);
    }

    @Test
    @DisplayName("a bare CancellationException (Spring JDK-client read-timeout race) is ambiguous on the SMSClub path")
    void should_classifyAsAmbiguous_when_bareCancellationIsThrown() {
        CancellationException cancelled = new CancellationException();

        boolean unknown = SmsTransportErrors.isOutcomeUnknownOrCancelled(cancelled);

        assertThat(unknown).isTrue();
    }

    @Test
    @DisplayName("a CancellationException buried in a non-I/O wrapper is ambiguous on the SMSClub path")
    void should_classifyAsAmbiguous_when_cancellationIsInTheCauseChain() {
        IllegalStateException wrapped = new IllegalStateException("x", new CancellationException());

        boolean unknown = SmsTransportErrors.isOutcomeUnknownOrCancelled(wrapped);

        assertThat(unknown).isTrue();
    }

    @Test
    @DisplayName("a cancellation with a pre-send cause in its chain stays definite")
    void should_notBeAmbiguous_when_cancellationCarriesPreSendCause() {
        CancellationException cancelled = new CancellationException();
        cancelled.initCause(new ConnectException("refused"));

        boolean unknown = SmsTransportErrors.isOutcomeUnknownOrCancelled(cancelled);

        assertThat(unknown).isFalse();
    }

    @Test
    @DisplayName("the opt-in guard keeps every existing verdict: non-I/O stays definite, read timeout stays ambiguous")
    void should_keepExistingVerdicts_when_noCancellationInChain() {
        IllegalStateException serialization = new IllegalStateException("x", new SocketTimeoutException());
        ResourceAccessException readTimeout = new ResourceAccessException("I/O", new HttpTimeoutException("t"));

        boolean serializationUnknown = SmsTransportErrors.isOutcomeUnknownOrCancelled(serialization);
        boolean readTimeoutUnknown = SmsTransportErrors.isOutcomeUnknownOrCancelled(readTimeout);

        assertThat(serializationUnknown).isFalse();
        assertThat(readTimeoutUnknown).isTrue();
    }

    @Test
    @DisplayName("isOutcomeUnknown (single-argument form) does not treat a bare CancellationException as ambiguous")
    void should_keepTurbosmsClassificationUnchanged_when_bareCancellationIsThrown() {
        CancellationException cancelled = new CancellationException();

        boolean unknown = SmsTransportErrors.isOutcomeUnknown(cancelled);

        assertThat(unknown).isFalse();
    }
}
