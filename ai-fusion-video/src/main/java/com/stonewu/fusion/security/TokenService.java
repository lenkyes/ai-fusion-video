package com.stonewu.fusion.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

/**
 * Redis-backed token service.
 *
 * <p>Each login creates an independent access/refresh token pair, so the same user can stay
 * signed in on multiple devices. Both tokens expire after 24 hours. Calling refresh within that
 * window rotates the pair and gives the current device a fresh 24-hour window.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TokenService {

    private static final String ACCESS_TOKEN_PREFIX = "fusion:token:";
    private static final String REFRESH_TOKEN_PREFIX = "fusion:refresh_token:";
    private static final String REFRESH_REPLAY_PREFIX = "fusion:refresh_replay:";
    private static final String ACCESS_TOKEN_REFRESH_SUFFIX = ":refresh";
    private static final String REFRESH_TOKEN_ACCESS_SUFFIX = ":access";

    private static final Duration TOKEN_EXPIRE_DURATION = Duration.ofHours(24);
    private static final Duration REFRESH_REPLAY_DURATION = Duration.ofSeconds(30);
    private static final long TOKEN_EXPIRE_SECONDS = TOKEN_EXPIRE_DURATION.toSeconds();

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TokenSession {
        private Long userId;
        private String username;
        private Long currentTeamId;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TokenPair {
        private String accessToken;
        private String refreshToken;
        private long expiresIn;
    }

    public TokenPair createToken(Long userId, String username, Long currentTeamId) {
        String accessToken = generateUUID();
        String refreshToken = generateUUID();
        writeTokenPair(accessToken, refreshToken, new TokenSession(userId, username, currentTeamId));

        return new TokenPair(accessToken, refreshToken, TOKEN_EXPIRE_SECONDS);
    }

    public synchronized TokenPair refreshAccessToken(String refreshToken) {
        TokenSession session = getRefreshTokenSession(refreshToken);
        if (session == null) {
            return getRefreshReplay(refreshToken);
        }

        String oldAccessToken = redisTemplate.opsForValue()
                .get(REFRESH_TOKEN_PREFIX + refreshToken + REFRESH_TOKEN_ACCESS_SUFFIX);
        if (oldAccessToken != null) {
            redisTemplate.delete(ACCESS_TOKEN_PREFIX + oldAccessToken);
            redisTemplate.delete(ACCESS_TOKEN_PREFIX + oldAccessToken + ACCESS_TOKEN_REFRESH_SUFFIX);
        }

        redisTemplate.delete(REFRESH_TOKEN_PREFIX + refreshToken);
        redisTemplate.delete(REFRESH_TOKEN_PREFIX + refreshToken + REFRESH_TOKEN_ACCESS_SUFFIX);

        String newAccessToken = generateUUID();
        String newRefreshToken = generateUUID();
        writeTokenPair(newAccessToken, newRefreshToken,
                new TokenSession(session.getUserId(), session.getUsername(), session.getCurrentTeamId()));
        TokenPair tokenPair = new TokenPair(newAccessToken, newRefreshToken, TOKEN_EXPIRE_SECONDS);
        writeRefreshReplay(refreshToken, tokenPair);
        return tokenPair;
    }

    public Long getUserIdFromToken(String token) {
        TokenSession session = getAccessTokenSession(token);
        return session != null ? session.getUserId() : null;
    }

    public String getUsernameFromToken(String token) {
        TokenSession session = getAccessTokenSession(token);
        return session != null ? session.getUsername() : null;
    }

    public Long getCurrentTeamIdFromToken(String token) {
        TokenSession session = getAccessTokenSession(token);
        return session != null ? session.getCurrentTeamId() : null;
    }

    public TokenSession getAccessTokenSession(String token) {
        return deserializeSession(redisTemplate.opsForValue().get(ACCESS_TOKEN_PREFIX + token));
    }

    public boolean updateCurrentTeam(String accessToken, Long expectedUserId, Long currentTeamId) {
        TokenSession session = getAccessTokenSession(accessToken);
        if (session == null || session.getUserId() == null) {
            return false;
        }
        if (expectedUserId != null && !expectedUserId.equals(session.getUserId())) {
            return false;
        }

        TokenSession updatedSession = new TokenSession(session.getUserId(), session.getUsername(), currentTeamId);
        String accessTokenKey = ACCESS_TOKEN_PREFIX + accessToken;
        writeSession(accessTokenKey, updatedSession, redisTemplate.getExpire(accessTokenKey, TimeUnit.SECONDS),
                TOKEN_EXPIRE_DURATION);

        String refreshToken = redisTemplate.opsForValue()
                .get(ACCESS_TOKEN_PREFIX + accessToken + ACCESS_TOKEN_REFRESH_SUFFIX);
        if (refreshToken != null) {
            String refreshTokenKey = REFRESH_TOKEN_PREFIX + refreshToken;
            writeSession(refreshTokenKey, updatedSession, redisTemplate.getExpire(refreshTokenKey, TimeUnit.SECONDS),
                    TOKEN_EXPIRE_DURATION);
        }
        return true;
    }

    public boolean validateToken(String token) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(ACCESS_TOKEN_PREFIX + token));
    }

    public void removeToken(String token) {
        String refreshToken = redisTemplate.opsForValue()
                .get(ACCESS_TOKEN_PREFIX + token + ACCESS_TOKEN_REFRESH_SUFFIX);
        if (refreshToken != null) {
            redisTemplate.delete(REFRESH_TOKEN_PREFIX + refreshToken);
            redisTemplate.delete(REFRESH_TOKEN_PREFIX + refreshToken + REFRESH_TOKEN_ACCESS_SUFFIX);
        }

        redisTemplate.delete(ACCESS_TOKEN_PREFIX + token);
        redisTemplate.delete(ACCESS_TOKEN_PREFIX + token + ACCESS_TOKEN_REFRESH_SUFFIX);
    }

    private void writeTokenPair(String accessToken, String refreshToken, TokenSession session) {
        String userValue = serializeSession(session);

        redisTemplate.opsForValue().set(ACCESS_TOKEN_PREFIX + accessToken, userValue, TOKEN_EXPIRE_DURATION);
        redisTemplate.opsForValue().set(REFRESH_TOKEN_PREFIX + refreshToken, userValue, TOKEN_EXPIRE_DURATION);
        redisTemplate.opsForValue().set(ACCESS_TOKEN_PREFIX + accessToken + ACCESS_TOKEN_REFRESH_SUFFIX,
                refreshToken, TOKEN_EXPIRE_DURATION);
        redisTemplate.opsForValue().set(REFRESH_TOKEN_PREFIX + refreshToken + REFRESH_TOKEN_ACCESS_SUFFIX,
                accessToken, TOKEN_EXPIRE_DURATION);
    }

    private String generateUUID() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private TokenSession getRefreshTokenSession(String refreshToken) {
        return deserializeSession(redisTemplate.opsForValue().get(REFRESH_TOKEN_PREFIX + refreshToken));
    }

    private void writeRefreshReplay(String refreshToken, TokenPair tokenPair) {
        try {
            redisTemplate.opsForValue().set(REFRESH_REPLAY_PREFIX + refreshToken,
                    objectMapper.writeValueAsString(tokenPair), REFRESH_REPLAY_DURATION);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize refresh replay", e);
        }
    }

    private TokenPair getRefreshReplay(String refreshToken) {
        String rawValue = redisTemplate.opsForValue().get(REFRESH_REPLAY_PREFIX + refreshToken);
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(rawValue, TokenPair.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize refresh replay", e);
            return null;
        }
    }

    private String serializeSession(TokenSession session) {
        try {
            return objectMapper.writeValueAsString(session);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize token session", e);
        }
    }

    private void writeSession(String key, TokenSession session, Long ttlSeconds, Duration fallbackTtl) {
        String rawValue = serializeSession(session);
        if (ttlSeconds != null && ttlSeconds > 0) {
            redisTemplate.opsForValue().set(key, rawValue, Duration.ofSeconds(ttlSeconds));
            return;
        }
        redisTemplate.opsForValue().set(key, rawValue, fallbackTtl);
    }

    private TokenSession deserializeSession(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(rawValue, TokenSession.class);
        } catch (Exception ignored) {
            String[] parts = rawValue.split(":", 2);
            try {
                Long userId = Long.parseLong(parts[0]);
                String username = parts.length > 1 ? parts[1] : null;
                return new TokenSession(userId, username, null);
            } catch (Exception ex) {
                log.warn("Failed to deserialize token session: {}", rawValue, ex);
                return null;
            }
        }
    }
}
