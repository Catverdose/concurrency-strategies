package com.mycom.concurrencystrategies.coupon.service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.stereotype.Service;

import com.mycom.concurrencystrategies.coupon.dto.CouponIssueRequest;
import com.mycom.concurrencystrategies.coupon.dto.CouponIssueResponse;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class JvmLockCouponIssueService {

    private final DirectCouponIssueService directCouponIssueService;

    private final ConcurrentMap<Long, ReentrantLock> locks =
            new ConcurrentHashMap<>();

    public CouponIssueResponse issue(
            Long couponId,
            CouponIssueRequest request) {

        ReentrantLock lock = locks.computeIfAbsent(
                couponId,
                id -> new ReentrantLock()
        );

        lock.lock();

        try {
            return directCouponIssueService.issue(
                    couponId,
                    request
            );
        } finally {
            lock.unlock();
        }
    }
}
