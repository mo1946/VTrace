package com.vtrace.business.analysis.model;

import java.time.LocalDateTime;

/** Read-only projection of the existing Python analysis_tasks table. */
public record AnalysisTask(
        String taskId,
        Integer mediaId,
        String state,
        String stage,
        Integer attemptCount,
        Integer progressCurrent,
        Integer progressTotal,
        String progressMessage,
        String error,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String report) {
}
