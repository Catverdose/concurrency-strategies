package com.mycom.concurrencystrategies.coupon.service.redis;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Component
public class RedisLockStockReservationStrategy implements RedisStockReservationStrategy {

    private static final Logger log =
            LoggerFactory.getLogger(RedisLockStockReservationStrategy.class);

    private static final int MAX_LOCK_ATTEMPTS = 20;
    private static final Duration LOCK_TTL = Duration.ofSeconds(3);
    private static final Duration RETRY_DELAY = Duration.ofMillis(5);

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> releaseLockScript;

    public RedisLockStockReservationStrategy(
            StringRedisTemplate redisTemplate,
            @Qualifier("releaseLockScript") RedisScript<Long> releaseLockScript) {
        this.redisTemplate = redisTemplate;
        this.releaseLockScript = releaseLockScript;
    }

    @Override
    public RedisCouponStrategy strategy() {
        return RedisCouponStrategy.REDIS_LOCK;
    }

    @Override
    public RedisReservationResult reserve(RedisStockKeys keys) {
        String token = UUID.randomUUID().toString();
        if (!acquire(keys.lock(), token)) {
            return RedisReservationResult.RETRY_EXHAUSTED;
        }

        try {
            String value = redisTemplate.opsForValue().get(keys.stock());
            if (value == null) {
                return RedisReservationResult.NOT_INITIALIZED;
            }

            long stock = parseStock(value, keys.stock());
            if (stock <= 0) {
                return RedisReservationResult.SOLD_OUT;
            }

            Long remaining = redisTemplate.opsForValue().decrement(keys.stock());
            return remaining == null
                    ? RedisReservationResult.ERROR
                    : RedisReservationResult.SUCCESS;
        } finally {
            release(keys.lock(), token);
        }
    }

    private boolean acquire(String lockKey, String token) {
        for (int attempt = 0; attempt < MAX_LOCK_ATTEMPTS; attempt++) {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(lockKey, token, LOCK_TTL);
            if (Boolean.TRUE.equals(acquired)) {
                return true;
            }
            if (!pauseBeforeRetry()) {
                return false;
            }
        }
        return false;
    }

    private boolean pauseBeforeRetry() {
        try {
            Thread.sleep(RETRY_DELAY.toMillis());
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void release(String lockKey, String token) {
        try {
            redisTemplate.execute(releaseLockScript, List.of(lockKey), token);
        } catch (RuntimeException exception) {
            log.error("Failed to release Redis experiment lock. lockKey={}", lockKey, exception);
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
}
