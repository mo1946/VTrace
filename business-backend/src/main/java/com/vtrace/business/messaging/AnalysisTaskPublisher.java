package com.vtrace.business.messaging;

public interface AnalysisTaskPublisher {
    /** True only for SEND_OK; false means delivery failed or its result is uncertain. */
    boolean publish(String taskId);
}
