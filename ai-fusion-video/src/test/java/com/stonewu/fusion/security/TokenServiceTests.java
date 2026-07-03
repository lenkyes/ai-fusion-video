package com.stonewu.fusion.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenServiceTests {

    private static final String ACCESS_TOKEN_PREFIX = "fusion:token:";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private final Map<String, String> redisValues = new HashMap<>();
    private TokenService tokenService;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        doAnswer(invocation -> {
            redisValues.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(valueOperations).set(anyString(), anyString(), any(Duration.class));
        when(valueOperations.get(anyString())).thenAnswer(invocation -> redisValues.get(invocation.getArgument(0)));
        lenient().when(redisTemplate.delete(anyString()))
                .thenAnswer(invocation -> redisValues.remove(invocation.getArgument(0)) != null);

        tokenService = new TokenService(redisTemplate, new ObjectMapper());
    }

    @Test
    void createTokenShouldKeepExistingSessionsForSameUser() {
        TokenService.TokenPair first = tokenService.createToken(1L, "stone", 10L);
        TokenService.TokenPair second = tokenService.createToken(1L, "stone", 10L);

        assertNotEquals(first.getAccessToken(), second.getAccessToken());
        assertNotNull(tokenService.getAccessTokenSession(first.getAccessToken()));
        assertNotNull(tokenService.getAccessTokenSession(second.getAccessToken()));
        assertEquals(24 * 60 * 60, first.getExpiresIn());
        assertEquals(24 * 60 * 60, second.getExpiresIn());
    }

    @Test
    void refreshAccessTokenShouldOnlyRotateCurrentSession() {
        TokenService.TokenPair first = tokenService.createToken(1L, "stone", 10L);
        TokenService.TokenPair second = tokenService.createToken(1L, "stone", 10L);

        TokenService.TokenPair refreshed = tokenService.refreshAccessToken(first.getRefreshToken());

        assertNull(tokenService.getAccessTokenSession(first.getAccessToken()));
        assertNotNull(tokenService.getAccessTokenSession(refreshed.getAccessToken()));
        assertNotNull(tokenService.getAccessTokenSession(second.getAccessToken()));
        assertNull(redisValues.get(ACCESS_TOKEN_PREFIX + first.getAccessToken()));
    }
}
