package com.vtrace.business.analysis.model;

import java.time.LocalDateTime;

public record NewAnalysisTask(String taskId, int mediaId, int userId, String goal,
                              String goalHash, String activeKey, int maxAttempts, LocalDateTime now) { }
