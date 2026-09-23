package com.mycom.concurrencystrategies.coupon.dto;

import lombok.Builder;

@Builder
public record CreateCouponRequest(Integer quantity) {
}
