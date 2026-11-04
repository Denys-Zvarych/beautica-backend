package com.beautica.booking.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.beautica.booking.crypto.GuestCancelTokenCipher;
import com.beautica.booking.crypto.GuestCancelTokenCipher.Row;
import com.beautica.booking.crypto.GuestCancelTokenCipherFixtures;
import com.beautica.booking.crypto.GuestCancelTokenCipherProperties;
import com.beautica.booking.entity.Appointment;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.repository.AppointmentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("GuestBookingPageLinks — phase 435 guest's own booking page from the sealed token")
class GuestBookingPageLinksTest {

    private static final String FRONTEND = "https://beautica.app";
    private static final UUID TOKEN = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final UUID BOOKING_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID HEADER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final GuestCancelTokenCipher CIPHER = GuestCancelTokenCipherFixtures.testCipher();

    @Mock private AppointmentRepository appointmentRepository;

    private GuestBookingPageLinks links;
    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach
    void setUp() {
        links = new GuestBookingPageLinks(CIPHER, new GuestCancelUrlBuilder(FRONTEND), appointmentRepository);
        logs = new ListAppender<>();
        logs.start();
        logger = (Logger) LoggerFactory.getLogger(GuestBookingPageLinks.class);
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    @Test
    @DisplayName("a standalone booking with a sealed token links its own page")
    void should_buildOwnPage_when_standaloneBookingSealed() {
        // Arrange
        Booking booking = Booking.builder().id(BOOKING_ID).cancelToken(TOKEN)
                .cancelTokenSealed(CIPHER.seal(TOKEN, Row.booking(BOOKING_ID))).build();

        // Act + Assert
        assertThat(links.forBooking(booking)).contains(FRONTEND + "/book/cancel/" + TOKEN);
    }

    @Test
    @DisplayName("a visit item links the loaded header's token, never its own")
    void should_buildHeaderPage_when_bookingInVisit() {
        // Arrange
        UUID itemToken = UUID.randomUUID();
        Appointment header = Appointment.builder().id(HEADER_ID).cancelToken(TOKEN)
                .cancelTokenSealed(CIPHER.seal(TOKEN, Row.appointment(HEADER_ID))).build();
        Booking item = Booking.builder().id(BOOKING_ID).cancelToken(itemToken)
                .cancelTokenSealed(CIPHER.seal(itemToken, Row.booking(BOOKING_ID)))
                .appointment(header).build();

        // Act + Assert
        assertThat(links.forBooking(item)).contains(FRONTEND + "/book/cancel/" + TOKEN);
        verifyNoInteractions(appointmentRepository);
    }

    @Test
    @DisplayName("a row created before V230 (no sealed copy) has no link")
    void should_returnEmpty_when_noSealedCopy() {
        // Act + Assert
        assertThat(links.forBooking(Booking.builder().cancelToken(TOKEN).build())).isEmpty();
        assertThat(links.forBooking(null)).isEmpty();
    }

    @Test
    @DisplayName("an undecryptable value drops the link and logs the booking id only — never token or sealed value")
    void should_dropLinkAndLogIdOnly_when_sealedValueDoesNotOpen() {
        // Arrange
        String foreign = new GuestCancelTokenCipher(
                new GuestCancelTokenCipherProperties("AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=")).seal(TOKEN, Row.booking(BOOKING_ID));
        Booking booking = Booking.builder().id(BOOKING_ID).cancelToken(TOKEN).cancelTokenSealed(foreign).build();

        // Act
        var link = links.forBooking(booking);

        // Assert
        assertThat(link).isEmpty();
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage()).contains(BOOKING_ID.toString())
                    .doesNotContain(TOKEN.toString()).doesNotContain(foreign);
            assertThat(event.getThrowableProxy()).as("no stack trace / message echo").isNull();
        });
    }

    @Test
    @DisplayName("the sealed copy is attachable only on the instance that minted the token")
    void should_refuseAttach_when_noInMemoryToken() {
        // Act + Assert
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> Booking.builder().build().attachSealedCancelToken(CIPHER.seal(TOKEN, Row.booking(BOOKING_ID))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("audit-fix: a sealed value copied onto ANOTHER booking row does not open there — no link")
    void should_dropLink_when_sealedValueCopiedToOtherRow() {
        // Arrange
        String sealedForOther = CIPHER.seal(TOKEN, Row.booking(UUID.randomUUID()));
        Booking booking = Booking.builder().id(BOOKING_ID).cancelToken(TOKEN).cancelTokenSealed(sealedForOther).build();

        // Act + Assert
        assertThat(links.forBooking(booking)).isEmpty();
    }
}
