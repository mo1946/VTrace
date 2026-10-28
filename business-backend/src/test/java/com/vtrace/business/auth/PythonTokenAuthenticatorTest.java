package com.vtrace.business.auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.vtrace.business.infrastructure.RedisTaskGuard;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PythonTokenAuthenticatorTest {
    private final UserMapper users = mock(UserMapper.class);
    private final RedisTaskGuard redis = mock(RedisTaskGuard.class);
    private final PythonTokenAuthenticator auth = new PythonTokenAuthenticator("test-secret", users, redis);

    @Test
    void acceptsHs256SubjectAndJti() {
        when(users.findActiveUser(7)).thenReturn(7);
        assertThat(auth.authenticate("bEaReR " + token("test-secret", Instant.now().plusSeconds(60), "jti")))
                .isEqualTo(7);
    }

    @Test
    void rejectsExpiredWrongSignatureMissingJtiAndMissingHeader() {
        for (String header : new String[]{null, "Bearer invalid",
                "Bearer " + token("wrong", Instant.now().plusSeconds(60), "jti"),
                "Bearer " + token("test-secret", Instant.now().minusSeconds(1), "jti"),
                "Bearer " + token("test-secret", Instant.now().plusSeconds(60), null)}) {
            assertThatThrownBy(() -> auth.authenticate(header)).isInstanceOfSatisfying(
                    ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(401));
        }
        verifyNoInteractions(users);
    }

    @Test
    void rejectsRevokedAndDisabledUsers() {
        String header = "Bearer " + token("test-secret", Instant.now().plusSeconds(60), "jti");
        when(redis.isRevoked("jti")).thenReturn(true);
        assertThatThrownBy(() -> auth.authenticate(header)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(users);
        when(redis.isRevoked("jti")).thenReturn(false);
        when(users.findActiveUser(7)).thenReturn(null);
        assertThatThrownBy(() -> auth.authenticate(header)).isInstanceOf(ResponseStatusException.class);
    }

    private String token(String secret, Instant expires, String jti) {
        var builder = JWT.create().withSubject("7").withExpiresAt(expires);
        if (jti != null) builder.withJWTId(jti);
        return builder.sign(Algorithm.HMAC256(secret));
    }
}
