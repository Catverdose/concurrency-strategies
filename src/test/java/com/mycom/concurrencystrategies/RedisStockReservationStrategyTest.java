package com.mycom.concurrencystrategies;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import com.mycom.concurrencystrategies.coupon.service.redis.RedisCouponStrategy;
import com.mycom.concurrencystrategies.coupon.service.redis.RedisDecrStockReservationStrategy;
import com.mycom.concurrencystrategies.coupon.service.redis.RedisLockStockReservationStrategy;
import com.mycom.concurrencystrategies.coupon.service.redis.RedisLuaStockReservationStrategy;
import com.mycom.concurrencystrategies.coupon.service.redis.RedisReservationResult;
import com.mycom.concurrencystrategies.coupon.service.redis.RedisStockKeys;
import com.mycom.concurrencystrategies.coupon.service.redis.RedisWatchStockReservationStrategy;

class RedisStockReservationStrategyTest {

    private static final RedisStockKeys KEYS = RedisStockKeys.of(
            "r01",
            RedisCouponStrategy.REDIS_LUA,
            10L);

    @Test
    void decrRestoresTemporaryNegativeStockBeforeReturningSoldOut() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> operations = valueOperations();
        when(redisTemplate.opsForValue()).thenReturn(operations);
        when(operations.decrement(KEYS.stock())).thenReturn(-1L);

        RedisReservationResult result =
                new RedisDecrStockReservationStrategy(redisTemplate).reserve(KEYS);

        assertThat(result).isEqualTo(RedisReservationResult.SOLD_OUT);
        verify(operations).increment(KEYS.stock());
    }

    @Test
    void luaMapsSuccessSoldOutAndMissingKeyResults() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        RedisScript<Long> reserveScript = redisScript();
        when(redisTemplate.execute(same(reserveScript), eq(List.of(KEYS.stock()))))
                .thenReturn(1L, 0L, -1L);
        RedisLuaStockReservationStrategy strategy =
                new RedisLuaStockReservationStrategy(redisTemplate, reserveScript);

        assertThat(strategy.reserve(KEYS)).isEqualTo(RedisReservationResult.SUCCESS);
        assertThat(strategy.reserve(KEYS)).isEqualTo(RedisReservationResult.SOLD_OUT);
        assertThat(strategy.reserve(KEYS)).isEqualTo(RedisReservationResult.NOT_INITIALIZED);
    }

    @Test
    void lockUsesTokenSafeScriptToReleaseAfterReservation() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> operations = valueOperations();
        RedisScript<Long> releaseScript = redisScript();
        when(redisTemplate.opsForValue()).thenReturn(operations);
        when(operations.setIfAbsent(eq(KEYS.lock()), anyString(), any(Duration.class)))
                .thenReturn(true);
        when(operations.get(KEYS.stock())).thenReturn("1");
        when(operations.decrement(KEYS.stock())).thenReturn(0L);

        RedisReservationResult result = new RedisLockStockReservationStrategy(
                redisTemplate,
                releaseScript).reserve(KEYS);

        assertThat(result).isEqualTo(RedisReservationResult.SUCCESS);
        verify(redisTemplate).execute(
                same(releaseScript),
                eq(List.of(KEYS.lock())),
                anyString());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void watchRetriesConflictAndThenCommitsReservation() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        RedisOperations<String, String> redisOperations = mock(RedisOperations.class);
        ValueOperations<String, String> operations = valueOperations();
        when(redisOperations.opsForValue()).thenReturn(operations);
        when(operations.get(KEYS.stock())).thenReturn("1");
        when(redisOperations.exec()).thenReturn(List.of(), List.of(0L));
        when(redisTemplate.execute(any(SessionCallback.class))).thenAnswer(invocation -> {
            SessionCallback callback = invocation.getArgument(0);
            return callback.execute(redisOperations);
        });

        RedisReservationResult result =
                new RedisWatchStockReservationStrategy(redisTemplate).reserve(KEYS);

        assertThat(result).isEqualTo(RedisReservationResult.SUCCESS);
        verify(redisOperations, times(2)).watch(KEYS.stock());
        verify(redisOperations, times(2)).multi();
        verify(operations, times(2)).decrement(KEYS.stock());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void watchUnwatchesWhenStockKeyIsMissing() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        RedisOperations<String, String> redisOperations = mock(RedisOperations.class);
        ValueOperations<String, String> operations = valueOperations();
        when(redisOperations.opsForValue()).thenReturn(operations);
        when(operations.get(KEYS.stock())).thenReturn(null);
        when(redisTemplate.execute(any(SessionCallback.class))).thenAnswer(invocation -> {
            SessionCallback callback = invocation.getArgument(0);
            return callback.execute(redisOperations);
        });

        RedisReservationResult result =
                new RedisWatchStockReservationStrategy(redisTemplate).reserve(KEYS);

        assertThat(result).isEqualTo(RedisReservationResult.NOT_INITIALIZED);
        verify(redisOperations).unwatch();
    }

    @SuppressWarnings("unchecked")
    private ValueOperations<String, String> valueOperations() {
        return mock(ValueOperations.class);
    }

    @SuppressWarnings("unchecked")
    private RedisScript<Long> redisScript() {
        return mock(RedisScript.class);
    }
}
