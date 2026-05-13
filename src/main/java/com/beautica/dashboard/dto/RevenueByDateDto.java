package com.beautica.dashboard.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RevenueByDateDto(
        LocalDate date,
        Long bookingCount,
        BigDecimal revenue
) {}
