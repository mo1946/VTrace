package com.vtrace.business.analysis.service;

import com.vtrace.business.analysis.dto.TaskSubmissionResponse;
import com.vtrace.business.analysis.mapper.AnalysisTaskMapper;
import com.vtrace.business.analysis.model.NewAnalysisTask;
import com.vtrace.business.infrastructure.RedisTaskGuard;
import com.vtrace.business.messaging.AnalysisTaskPublisher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AnalysisTaskSubmissionService {
    public static final String DEFAULT_GOAL = "概括视频主要内容和核心观点，并引用有代表性的时间戳证据";
    private final AnalysisTaskMapper mapper;
    private final AnalysisTaskWriter writer;
    private final RedisTaskGuard guard;
    private final AnalysisTaskPublisher publisher;
    private final int maxAttempts;

    public AnalysisTaskSubmissionService(AnalysisTaskMapper mapper, AnalysisTaskWriter writer,
            RedisTaskGuard guard, AnalysisTaskPublisher publisher,
            @Value("${vtrace.max-analysis-attempts}") int maxAttempts) {
        if (maxAttempts < 1) throw new IllegalArgumentException("MAX_ANALYSIS_ATTEMPTS must be positive");
        this.mapper = mapper;
        this.writer = writer;
        this.guard = guard;
        this.publisher = publisher;
        this.maxAttempts = maxAttempts;
    }

    public TaskSubmissionResponse submit(int userId, String rawMediaId, String rawGoal) {
        guard.enforceAnalysisRate(userId);
        int mediaId;
        try {
            mediaId = Integer.parseInt(rawMediaId == null ? "" : pythonStrip(rawMediaId));
        } catch (NumberFormatException exception) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "id 必须是整数");
        }
        String goal = normalizeGoal(rawGoal);
        if (mapper.findOwnedMedia(mediaId, userId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "媒体不存在");
        }
        String hash = goalHash(goal);
        String active = mapper.findActiveTask(mediaId, hash);
        if (active != null) throw new DuplicateTaskException(active);
        String taskId = UUID.randomUUID().toString();
        String lockKey = lockKey(mediaId, hash);
        var acquisition = guard.acquire(lockKey, taskId);
        if (acquisition == RedisTaskGuard.Acquisition.BUSY) {
            throw new DuplicateTaskException(mapper.findActiveTask(mediaId, hash));
        }
        try {
            writer.create(new NewAnalysisTask(taskId, mediaId, userId, goal, hash,
                    mediaId + ":" + hash, maxAttempts, LocalDateTime.now(ZoneOffset.UTC)));
        } catch (DuplicateKeyException exception) {
            if (acquisition == RedisTaskGuard.Acquisition.ACQUIRED) guard.release(lockKey, taskId);
            // The writer transaction has rolled back before this fresh lookup.
            String winner = mapper.findActiveTask(mediaId, hash);
            if (winner != null) throw new DuplicateTaskException(winner);
            throw exception;
        } catch (RuntimeException exception) {
            if (acquisition == RedisTaskGuard.Acquisition.ACQUIRED) guard.release(lockKey, taskId);
            throw exception;
        }
        // No database transaction is active here. On timeout retain the committed task and lock:
        // the broker may already have accepted it, and Python's QUEUED watchdog can claim it.
        boolean sent = publisher.publish(taskId);
        return new TaskSubmissionResponse(taskId,
                sent ? "任务已提交" : "任务已保存，消息投递待确认；等待 Worker 排队任务兜底",
                sent ? "SENT" : "PENDING");
    }

    public static String normalizeGoal(String rawGoal) {
        String value = rawGoal == null ? DEFAULT_GOAL : rawGoal;
        if (value.codePointCount(0, value.length()) > 500) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "分析目标不能超过 500 个字符");
        }
        String normalized = pythonStrip(value);
        if (normalized.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分析目标不能为空");
        }
        return normalized;
    }

    public static String goalHash(String goal) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(goal.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static String lockKey(int mediaId, String hash) {
        return "analysis:active:" + mediaId + ":" + hash.substring(0, 24);
    }

    private static String pythonStrip(String value) {
        int start = 0, end = value.length();
        while (start < end && whitespace(value.codePointAt(start))) start += Character.charCount(value.codePointAt(start));
        while (end > start && whitespace(value.codePointBefore(end))) end -= Character.charCount(value.codePointBefore(end));
        return value.substring(start, end);
    }

    private static boolean whitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) || codePoint == 0x85;
    }
}
