package com.mycom.concurrencystrategies.coupon.dto;

import lombok.Builder;

@Builder
public record CreateCouponResponse(
        Long couponId,
        int quantity
) {
}
