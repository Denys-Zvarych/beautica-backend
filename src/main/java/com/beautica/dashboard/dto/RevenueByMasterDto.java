package com.beautica.dashboard.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record RevenueByMasterDto(
        UUID masterId,
        // full name — caller must verify actor is the salon owner or the master themselves
        String masterName,
        Long bookingCount,
        BigDecimal revenue
) {}
