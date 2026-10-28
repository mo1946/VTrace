package com.vtrace.business.analysis.service;

public class DuplicateTaskException extends RuntimeException {
    private final String taskId;

    public DuplicateTaskException(String taskId) {
        super("相同任务正在处理中");
        this.taskId = taskId;
    }

    public String taskId() { return taskId; }
}
