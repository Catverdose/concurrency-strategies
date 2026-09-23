package com.mycom.concurrencystrategies.coupon.service.redis;

public record RedisStockKeys(String stock, String lock) {

    public static RedisStockKeys of(
            String runId,
            RedisCouponStrategy strategy,
            Long couponId) {
        String prefix = "experiment:%s:%s:coupon:%d"
                .formatted(runId, strategy.path(), couponId);
        return new RedisStockKeys(prefix + ":stock", prefix + ":lock");
    }
}
