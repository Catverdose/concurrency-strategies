package com.mycom.concurrencystrategies.coupon.service.redis;

public enum RedisReservationResult {
    SUCCESS,
    SOLD_OUT,
    NOT_INITIALIZED,
    RETRY_EXHAUSTED,
    ERROR
}
