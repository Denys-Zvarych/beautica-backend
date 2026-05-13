package com.beautica.dashboard.dto;

import java.math.BigDecimal;
import java.util.List;

public record RevenueResponse(
        Long totalCompletedBookings,
        BigDecimal estimatedRevenue,
        List<RevenueByMasterDto> byMaster,
        List<RevenueByServiceDto> byService,
        List<RevenueByDateDto> byDate
) {}
