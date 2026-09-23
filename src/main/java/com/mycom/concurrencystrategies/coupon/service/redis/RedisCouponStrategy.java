package com.mycom.concurrencystrategies.coupon.service.redis;

import java.util.Arrays;

import com.mycom.concurrencystrategies.global.exception.InvalidExperimentRequestException;

public enum RedisCouponStrategy {
    REDIS_LOCK("redis-lock"),
    REDIS_DECR("redis-decr"),
    REDIS_LUA("redis-lua"),
    REDIS_WATCH("redis-watch");

    private final String path;

    RedisCouponStrategy(String path) {
        this.path = path;
    }

    public String path() {
        return path;
    }

    public static RedisCouponStrategy fromPath(String path) {
        return Arrays.stream(values())
                .filter(strategy -> strategy.path.equals(path))
                .findFirst()
                .orElseThrow(() -> new InvalidExperimentRequestException(
                        "Unsupported Redis strategy: " + path));
    }
}
