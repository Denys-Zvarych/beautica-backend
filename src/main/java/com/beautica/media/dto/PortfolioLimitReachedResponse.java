package com.beautica.media.dto;

import com.beautica.common.exception.PortfolioLimitReachedException;

/**
 * Response body for the {@code 409 PORTFOLIO_LIMIT_REACHED} returned when a portfolio upload would exceed
 * the per-entity cap. {@code code} is {@link PortfolioLimitReachedException#ERROR_CODE}; nothing else is
 * echoed (the cap value is product copy the client already holds).
 */
public record PortfolioLimitReachedResponse(String code) {}
