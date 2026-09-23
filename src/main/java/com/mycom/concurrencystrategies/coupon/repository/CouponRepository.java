package com.mycom.concurrencystrategies.coupon.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mycom.concurrencystrategies.coupon.entity.Coupon;

public interface CouponRepository extends JpaRepository<Coupon, Long> {
}
