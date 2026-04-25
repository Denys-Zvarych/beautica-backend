package com.beautica.master.dto;

import com.beautica.master.entity.ScheduleExceptionReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record ScheduleExceptionRequest(
        @NotNull LocalDate date,
        @NotNull ScheduleExceptionReason reason,
        @Size(max = 500) String note
) {
}
