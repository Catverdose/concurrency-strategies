package com.mycom.concurrencystrategies.coupon.service.redis;

import java.util.List;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class RedisWatchStockReservationStrategy implements RedisStockReservationStrategy {

    private static final int MAX_RETRY = 20;

    private final StringRedisTemplate redisTemplate;

    @Override
    public RedisCouponStrategy strategy() {
        return RedisCouponStrategy.REDIS_WATCH;
    }

    @Override
    public RedisReservationResult reserve(RedisStockKeys keys) {
        for (int attempt = 0; attempt < MAX_RETRY; attempt++) {
            WatchAttempt result = watchOnce(keys.stock());
            switch (result) {
                case SUCCESS:
                    return RedisReservationResult.SUCCESS;
                case SOLD_OUT:
                    return RedisReservationResult.SOLD_OUT;
                case NOT_INITIALIZED:
                    return RedisReservationResult.NOT_INITIALIZED;
                case ERROR:
                    return RedisReservationResult.ERROR;
                case CONFLICT:
                    break;
            }
        }
        return RedisReservationResult.RETRY_EXHAUSTED;
    }

    private WatchAttempt watchOnce(String stockKey) {
        WatchAttempt result = redisTemplate.execute(new SessionCallback<>() {
            @Override
            @SuppressWarnings({"unchecked", "rawtypes"})
            public <K, V> WatchAttempt execute(RedisOperations<K, V> operations)
                    throws DataAccessException {
                RedisOperations<String, String> stringOperations =
                        (RedisOperations) operations;
                boolean watching = false;
                boolean transactionStarted = false;

                try {
                    stringOperations.watch(stockKey);
                    watching = true;

                    String value = stringOperations.opsForValue().get(stockKey);
                    if (value == null) {
                        stringOperations.unwatch();
                        watching = false;
                        return WatchAttempt.NOT_INITIALIZED;
                    }
                    if (parseStock(value, stockKey) <= 0) {
                        stringOperations.unwatch();
                        watching = false;
                        return WatchAttempt.SOLD_OUT;
                    }

                    stringOperations.multi();
                    transactionStarted = true;
                    stringOperations.opsForValue().decrement(stockKey);
                    List<Object> transactionResult = stringOperations.exec();
                    transactionStarted = false;
                    watching = false;

                    if (transactionResult == null || transactionResult.isEmpty()) {
                        return WatchAttempt.CONFLICT;
                    }
                    return WatchAttempt.SUCCESS;
                } catch (RuntimeException exception) {
                    cleanup(stringOperations, transactionStarted, watching, exception);
                    throw exception;
                }
            }
        });
        return result == null ? WatchAttempt.ERROR : result;
    }

    private void cleanup(
            RedisOperations<String, String> operations,
            boolean transactionStarted,
            boolean watching,
            RuntimeException originalException) {
        try {
            if (transactionStarted) {
                operations.discard();
            } else if (watching) {
                operations.unwatch();
            }
        } catch (RuntimeException cleanupException) {
            originalException.addSuppressed(cleanupException);
        }
    }

    private long parseStock(String value, String stockKey) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(
                    "Redis stock is not numeric. stockKey=" + stockKey,
                    exception);
        }
    }

    private enum WatchAttempt {
        SUCCESS,
        SOLD_OUT,
        NOT_INITIALIZED,
        CONFLICT,
        ERROR
    }
}
