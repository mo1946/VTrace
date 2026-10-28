package com.vtrace.business.analysis.service;

import com.vtrace.business.analysis.dto.TaskStatusResponse;
import com.vtrace.business.analysis.mapper.AnalysisTaskMapper;
import com.vtrace.business.analysis.model.AnalysisTask;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AnalysisTaskService {
    private final AnalysisTaskMapper mapper;

    public AnalysisTaskService(AnalysisTaskMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public TaskStatusResponse getStatus(String taskId, int userId) {
        if (taskId == null || taskId.isBlank() || taskId.length() > 36) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "taskId 长度必须为 1 到 36 个字符");
        }
        AnalysisTask task = mapper.findByIdForUser(taskId, userId);
        if (task == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "分析任务不存在");
        }
        int current = nonNegative(task.progressCurrent());
        int total = nonNegative(task.progressTotal());
        // Python round() uses ties-to-even; keep the existing progress convention.
        int percent = total == 0 ? 0 : (int) Math.min(100, Math.rint(current * 100.0 / total));
        int attempts = nonNegative(task.attemptCount());
        String message = task.progressMessage();
        if (message == null || message.isEmpty()) {
            message = switch (task.state()) {
                case "QUEUED" -> "任务已排队";
                case "PROCESSING" -> "正在分析";
                case "RETRYING" -> "第 " + attempts + " 次执行失败，等待重试";
                case "COMPLETED" -> "分析完成";
                case "FAILED" -> task.error() == null || task.error().isEmpty() ? "分析失败" : task.error();
                default -> task.state();
            };
        }
        return new TaskStatusResponse(task.taskId(), task.mediaId(), task.state(), task.stage(),
                attempts, current, total, percent, message, task.error(), task.createdAt(), task.updatedAt(), task.report());
    }

    private static int nonNegative(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }
}
