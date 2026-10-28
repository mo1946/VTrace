package com.vtrace.business.infrastructure;

import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "VTRACE_TEST_REDIS_URL", matches = "redis://.*")
class RedisTaskGuardIntegrationTest {
    @Test
    void sharesPythonKeyTtlRevocationAndAtomicUnlock() {
        URI uri = URI.create(System.getenv("VTRACE_TEST_REDIS_URL"));
        var configuration = new RedisStandaloneConfiguration(uri.getHost(), uri.getPort());
        if (uri.getPath().length() > 1) configuration.setDatabase(Integer.parseInt(uri.getPath().substring(1)));
        var factory = new LettuceConnectionFactory(configuration);
        try {
            factory.afterPropertiesSet();
            factory.start();
            var template = new StringRedisTemplate(factory);
            var guard = new RedisTaskGuard(template, true);
            String key = "analysis:active:2147483647:" + UUID.randomUUID();
            String jti = "business-test-" + UUID.randomUUID();
            try {
                assertThat(guard.acquire(key, "owner")).isEqualTo(RedisTaskGuard.Acquisition.ACQUIRED);
                assertThat(template.getExpire(key)).isBetween(7190L, 7200L);
                assertThat(guard.acquire(key, "other")).isEqualTo(RedisTaskGuard.Acquisition.BUSY);
                guard.release(key, "other");
                assertThat(template.opsForValue().get(key)).isEqualTo("owner");
                guard.release(key, "owner");
                assertThat(template.hasKey(key)).isFalse();
                template.opsForValue().set("jwt:revoked:" + jti, "1");
                assertThat(guard.isRevoked(jti)).isTrue();
            } finally {
                template.delete(key);
                template.delete("jwt:revoked:" + jti);
            }
        } finally { factory.destroy(); }
    }

    @Test
    void appliesSharedTwentyPerHourRateRule() {
        URI uri = URI.create(System.getenv("VTRACE_TEST_REDIS_URL"));
        var factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(uri.getHost(), uri.getPort()));
        try {
            factory.afterPropertiesSet();
            factory.start();
            var template = new StringRedisTemplate(factory);
            var guard = new RedisTaskGuard(template, true);
            int userId = 1_000_000_000 + (int) (Math.random() * 1_000_000);
            String key = "rate:analysis:" + userId;
            try {
                for (int i = 0; i < 20; i++) guard.enforceAnalysisRate(userId);
                assertThatThrownBy(() -> guard.enforceAnalysisRate(userId)).isInstanceOfSatisfying(
                        ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(429));
                assertThat(template.getExpire(key)).isBetween(3590L, 3600L);
            } finally {
                template.delete(key);
            }
        } finally { factory.destroy(); }
    }
}
