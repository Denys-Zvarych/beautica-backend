package com.beautica.review.service;

import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.review.dto.CreateReviewRequest;
import com.beautica.review.dto.ReviewResponse;
import com.beautica.review.entity.Review;
import com.beautica.review.event.ReviewCreatedEvent;
import com.beautica.review.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final BookingRepository bookingRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public ReviewResponse createReview(UUID clientId, CreateReviewRequest request) {
        // clientId must come from Authentication in the controller — never from request body
        Booking booking = bookingRepository.findByIdWithFullGraph(request.bookingId())
                .orElseThrow(() -> new NotFoundException("Booking not found"));

        if (booking.getStatus() != BookingStatus.COMPLETED) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "Review can only be submitted for completed bookings");
        }

        if (!booking.getClient().getId().equals(clientId)) {
            throw new ForbiddenException("Not authorized to review this booking");
        }

        if (reviewRepository.findByBookingId(booking.getId()).isPresent()) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "Review already exists for this booking");
        }

        Review review = Review.builder()
                .booking(booking)
                .client(booking.getClient())
                .master(booking.getMaster())
                .salon(booking.getSalon())
                .rating(request.rating().shortValue())
                .comment(request.comment())
                .build();

        Review saved;
        try {
            saved = reviewRepository.saveAndFlush(review);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "Review already exists for this booking");
        }
        eventPublisher.publishEvent(new ReviewCreatedEvent(booking.getMaster().getId()));
        return ReviewResponse.from(saved);
    }

    @Cacheable(value = "reviews-by-master", key = "{#masterId, #pageable.pageNumber, #pageable.pageSize}")
    @Transactional(readOnly = true)
    public Page<ReviewResponse> getReviewsForMaster(UUID masterId, Pageable pageable) {
        Page<UUID> idPage = reviewRepository.findIdsByMasterIdOrderByCreatedAtDesc(masterId, pageable);
        if (idPage.isEmpty()) {
            return Page.empty(pageable);
        }
        List<UUID> ids = idPage.getContent();
        List<Review> reviews = reviewRepository.findByIdsWithGraph(ids);
        Map<UUID, Review> byId = reviews.stream()
                .collect(Collectors.toMap(Review::getId, r -> r));
        List<ReviewResponse> content = ids.stream()
                .map(id -> ReviewResponse.from(byId.get(id)))
                .toList();
        return new PageImpl<>(content, pageable, idPage.getTotalElements());
    }

    @Transactional(readOnly = true)
    public ReviewResponse getReview(UUID reviewId) {
        Review review = reviewRepository.findByIdWithAssociations(reviewId)
                .orElseThrow(() -> new NotFoundException("Review not found"));
        return ReviewResponse.from(review);
    }
}
