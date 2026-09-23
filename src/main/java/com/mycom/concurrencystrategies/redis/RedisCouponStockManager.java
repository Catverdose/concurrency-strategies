package com.mycom.concurrencystrategies.redis;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class RedisCouponStockManager {

    private static final String KEY_PREFIX =
            "experiment:coupon:stock:";

    private final StringRedisTemplate redisTemplate;

    public void initialize(Long couponId, int quantity) {
        redisTemplate.opsForValue()
                .set(key(couponId), String.valueOf(quantity));
    }

    public Long getRemainingQuantity(Long couponId) {
        String value =
                redisTemplate.opsForValue().get(key(couponId));

        return value == null ? null : Long.valueOf(value);
    }

    public void compensate(Long couponId) {
        redisTemplate.opsForValue().increment(key(couponId));
    }

    private String key(Long couponId) {
        return KEY_PREFIX + couponId;
    }
}