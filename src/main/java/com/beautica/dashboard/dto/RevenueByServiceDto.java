package com.beautica.dashboard.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record RevenueByServiceDto(
        UUID serviceDefId,
        String serviceName,
        Long bookingCount,
        BigDecimal revenue
) {}
