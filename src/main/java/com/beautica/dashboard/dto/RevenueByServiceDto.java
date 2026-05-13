package com.beautica.dashboard.dto;

import java.math.BigDecimal;
import java.util.UUID;

// Caller must be the authenticated SALON_OWNER or INDEPENDENT_MASTER for this scope.
public record RevenueByServiceDto(
        UUID serviceDefId,
        String serviceName,
        Long bookingCount,
        BigDecimal revenue
) {}
