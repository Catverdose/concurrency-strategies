package com.mycom.concurrencystrategies.coupon.service.redis;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class RedisDecrStockReservationStrategy implements RedisStockReservationStrategy {

    private final StringRedisTemplate redisTemplate;

    @Override
    public RedisCouponStrategy strategy() {
        return RedisCouponStrategy.REDIS_DECR;
    }

    @Override
    public RedisReservationResult reserve(RedisStockKeys keys) {
        Long remaining = redisTemplate.opsForValue().decrement(keys.stock());
        if (remaining == null) {
            return RedisReservationResult.ERROR;
        }
        if (remaining < 0) {
            redisTemplate.opsForValue().increment(keys.stock());
            return RedisReservationResult.SOLD_OUT;
        }
        return RedisReservationResult.SUCCESS;
    }
}
