package com.beautica.master.dto;

import com.beautica.master.entity.ScheduleExceptionReason;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record ScheduleExceptionRequest(
        @NotNull(message = "Date is required")
        @FutureOrPresent(message = "Date must be today or in the future")
        LocalDate date,
        @NotNull(message = "Reason is required") ScheduleExceptionReason reason,
        // TEXT column in DB — application cap at 2000 (§A). Control-char ban
        // prevents embedded NUL/newline reaching the DB and producing a 500.
        @Size(max = 2000, message = "Note must be at most 2000 characters")
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "Note must not contain control characters")
        String note
) {
}
