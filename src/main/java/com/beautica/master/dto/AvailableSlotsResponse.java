package com.beautica.master.dto;

import com.beautica.booking.dto.AvailableSlotResponse;

import java.time.LocalDate;
import java.util.List;

public record AvailableSlotsResponse(LocalDate date, List<AvailableSlotResponse> slots) {}
