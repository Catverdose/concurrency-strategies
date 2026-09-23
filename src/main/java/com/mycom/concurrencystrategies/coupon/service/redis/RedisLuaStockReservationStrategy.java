package com.mycom.concurrencystrategies.coupon.service.redis;

import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Component
public class RedisLuaStockReservationStrategy implements RedisStockReservationStrategy {

    private static final long NOT_INITIALIZED = -1L;
    private static final long SOLD_OUT = 0L;
    private static final long SUCCESS = 1L;

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> reserveStockScript;

    public RedisLuaStockReservationStrategy(
            StringRedisTemplate redisTemplate,
            @Qualifier("reserveStockScript") RedisScript<Long> reserveStockScript) {
        this.redisTemplate = redisTemplate;
        this.reserveStockScript = reserveStockScript;
    }

    @Override
    public RedisCouponStrategy strategy() {
        return RedisCouponStrategy.REDIS_LUA;
    }

    @Override
    public RedisReservationResult reserve(RedisStockKeys keys) {
        Long result = redisTemplate.execute(reserveStockScript, List.of(keys.stock()));
        if (result == null) {
            return RedisReservationResult.ERROR;
        }
        if (result == NOT_INITIALIZED) {
            return RedisReservationResult.NOT_INITIALIZED;
        }
        if (result == SOLD_OUT) {
            return RedisReservationResult.SOLD_OUT;
        }
        if (result == SUCCESS) {
            return RedisReservationResult.SUCCESS;
        }
        return RedisReservationResult.ERROR;
    }
}
