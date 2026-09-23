package com.mycom.concurrencystrategies.coupon.dto;

public enum CouponIssueResult {
    SUCCESS,
    SOLD_OUT,
    DUPLICATE_REQUEST,
    DUPLICATE_USER,
    COUPON_NOT_FOUND,
    REDIS_NOT_INITIALIZED,
    INVALID_REQUEST,
    INTERNAL_ERROR
}
