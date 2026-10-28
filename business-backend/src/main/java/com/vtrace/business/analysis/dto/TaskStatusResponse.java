package com.vtrace.business.analysis.dto;

import java.time.LocalDateTime;

public record TaskStatusResponse(
        String taskId,
        Integer mediaId,
        String state,
        String stage,
        int attemptCount,
        int progressCurrent,
        int progressTotal,
        int progressPercent,
        String message,
        String error,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String report) {
}
