package com.vtrace.business.infrastructure;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class RedisTaskGuard {
    public enum Acquisition { ACQUIRED, BUSY, UNAVAILABLE }
    private static final Logger log = LoggerFactory.getLogger(RedisTaskGuard.class);
    private static final DefaultRedisScript<Long> UNLOCK = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
              return redis.call('del', KEYS[1])
            end
            return 0
            """, Long.class);
    private static final DefaultRedisScript<Long> COUNT = new DefaultRedisScript<>("""
            local count = redis.call('incr', KEYS[1])
            if count == 1 then redis.call('expire', KEYS[1], 3600) end
            return count
            """, Long.class);
    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final Map<Integer, Window> localLimits = new ConcurrentHashMap<>();
    private record Window(int count, long expiresAt) { }

    public RedisTaskGuard(StringRedisTemplate redis, @Value("${vtrace.redis.enabled}") boolean enabled) {
        this.redis = redis;
        this.enabled = enabled;
    }

    public Acquisition acquire(String key, String taskId) {
        if (!enabled) return Acquisition.UNAVAILABLE;
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, taskId, Duration.ofSeconds(7200)))
                    ? Acquisition.ACQUIRED : Acquisition.BUSY;
        } catch (DataAccessException exception) {
            log.warn("redis_task_lock_unavailable; using database unique constraint");
            return Acquisition.UNAVAILABLE;
        }
    }

    public void release(String key, String taskId) {
        if (!enabled) return;
        try {
            redis.execute(UNLOCK, List.of(key), taskId);
        } catch (DataAccessException exception) {
            log.warn("redis_task_unlock_failed task_id={}", taskId);
        }
    }

    public boolean isRevoked(String jti) {
        if (!enabled) return false;
        try {
            return Boolean.TRUE.equals(redis.hasKey("jwt:revoked:" + jti));
        } catch (DataAccessException exception) {
            // Same optional-Redis behavior as Python when Redis cannot be connected.
            log.warn("redis_token_revocation_unavailable");
            return false;
        }
    }

    public void enforceAnalysisRate(int userId) {
        if (enabled) {
            try {
                Long count = redis.execute(COUNT, List.of("rate:analysis:" + userId));
                if (count != null) {
                    if (count > 20) rateLimited();
                    return;
                }
            } catch (DataAccessException exception) {
                log.warn("redis_rate_limit_unavailable user_id={}", userId);
            }
        }
        long now = System.nanoTime();
        Window window = localLimits.compute(userId, (id, old) -> old == null || now >= old.expiresAt()
                ? new Window(1, now + Duration.ofHours(1).toNanos())
                : new Window(old.count() + 1, old.expiresAt()));
        if (window.count() > 20) rateLimited();
    }

    private static void rateLimited() {
        throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请稍后再试");
    }
}
