package com.beautica.booking.entity;

import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.enums.CancellationReason;
import com.beautica.common.AuditableEntity;
import com.beautica.master.entity.Master;
import com.beautica.salon.entity.Salon;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "bookings",
        indexes = {
                // partial index: dashboard revenue query for INDEPENDENT_MASTER path
                @Index(name = "idx_bookings_master_completed_starts_at", columnList = "master_id, starts_at"),
                // partial index: dashboard revenue query for SALON_OWNER path
                @Index(name = "idx_bookings_salon_completed_starts_at", columnList = "salon_id, starts_at")
        }
)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Booking extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    private User client;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "master_id", nullable = false)
    private Master master;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "master_service_id", nullable = false)
    private MasterServiceAssignment masterService;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "salon_id")
    private Salon salon;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookingStatus status;

    @Column(name = "starts_at", nullable = false)
    private OffsetDateTime startsAt;

    @Column(name = "ends_at", nullable = false)
    private OffsetDateTime endsAt;

    @Column(name = "price_at_booking", nullable = false)
    private BigDecimal priceAtBooking;

    @Column(name = "duration_minutes_at_booking", nullable = false)
    private int durationMinutesAtBooking;

    @Column(name = "buffer_minutes_at_booking")
    private int bufferMinutesAtBooking;

    @Column(name = "idempotency_key", length = 64)
    private String idempotencyKey;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "cancellation_reason")
    private CancellationReason cancellationReason;

    @Column(name = "client_comment", length = 1000)
    private String clientComment;

    @Setter
    @Column(name = "provider_comment", length = 1000)
    private String providerComment;
}
