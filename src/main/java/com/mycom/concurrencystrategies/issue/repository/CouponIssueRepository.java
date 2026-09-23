package com.mycom.concurrencystrategies.issue.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mycom.concurrencystrategies.issue.entity.CouponIssue;

public interface CouponIssueRepository extends JpaRepository<CouponIssue, Long> {

    boolean existsByRequestId(String requestId);

    boolean existsByCoupon_IdAndUser_Id(Long couponId, Long userId);

    long countByCoupon_Id(Long couponId);

    long deleteByCoupon_Id(Long couponId);
}
