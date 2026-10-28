package com.vtrace.business.auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.vtrace.business.infrastructure.RedisTaskGuard;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Validates existing Python tokens; does not introduce a second login/token issuer. */
@Service
public class PythonTokenAuthenticator {
    private final JWTVerifier verifier;
    private final UserMapper users;
    private final RedisTaskGuard redis;

    public PythonTokenAuthenticator(@Value("${vtrace.jwt-secret}") String secret,
                                    UserMapper users, RedisTaskGuard redis) {
        this.verifier = JWT.require(Algorithm.HMAC256(secret)).withClaimPresence("jti").build();
        this.users = users;
        this.redis = redis;
    }

    public int authenticate(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        int userId;
        try {
            var jwt = verifier.verify(authorization.substring(7));
            String jti = jwt.getId();
            if (jti == null || jti.isEmpty() || redis.isRevoked(jti)) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效");
            }
            userId = Integer.parseInt(jwt.getSubject());
        } catch (JWTVerificationException | IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效");
        }
        if (users.findActiveUser(userId) == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户不存在或已禁用");
        }
        return userId;
    }
}
