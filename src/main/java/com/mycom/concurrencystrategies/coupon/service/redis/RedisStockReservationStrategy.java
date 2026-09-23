package com.mycom.concurrencystrategies.coupon.service.redis;

public interface RedisStockReservationStrategy {

    RedisCouponStrategy strategy();

    RedisReservationResult reserve(RedisStockKeys keys);
}
