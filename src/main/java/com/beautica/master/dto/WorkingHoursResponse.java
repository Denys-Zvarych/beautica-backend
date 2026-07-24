package com.beautica.master.dto;

import com.beautica.master.entity.WorkingHours;

import java.time.LocalTime;
import java.util.UUID;

public record WorkingHoursResponse(
        UUID id,
        int dayOfWeek,
        LocalTime startTime,
        LocalTime endTime,
        boolean isActive
) {
    public static WorkingHoursResponse from(WorkingHours wh) {
        return new WorkingHoursResponse(
                wh.getId(),
                wh.getDayOfWeek(),
                wh.getStartTime(),
                wh.getEndTime(),
                wh.isActive()
        );
    }
}
