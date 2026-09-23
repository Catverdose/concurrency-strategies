package com.mycom.concurrencystrategies.coupon.controller;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mycom.concurrencystrategies.coupon.dto.CouponIssueRequest;
import com.mycom.concurrencystrategies.coupon.dto.CouponIssueResponse;
import com.mycom.concurrencystrategies.coupon.dto.CouponStatusResponse;
import com.mycom.concurrencystrategies.coupon.dto.CreateCouponRequest;
import com.mycom.concurrencystrategies.coupon.dto.CreateCouponResponse;
import com.mycom.concurrencystrategies.coupon.service.ConditionalCouponIssueService;
import com.mycom.concurrencystrategies.coupon.service.DirectCouponIssueService;
import com.mycom.concurrencystrategies.coupon.service.ExperimentCouponService;
import com.mycom.concurrencystrategies.coupon.service.JvmLockCouponIssueService;
import com.mycom.concurrencystrategies.coupon.service.OptimisticCouponIssueService;
import com.mycom.concurrencystrategies.coupon.service.PessimisticCouponIssueService;
import com.mycom.concurrencystrategies.coupon.service.RedisCouponIssueService;
import com.mycom.concurrencystrategies.coupon.service.redis.RedisCouponStrategy;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Validated
@RestController
@RequestMapping("/experiment")
@RequiredArgsConstructor
public class ExperimentCouponController {

    private final ExperimentCouponService experimentCouponService;
    private final DirectCouponIssueService directCouponIssueService;
    private final JvmLockCouponIssueService jvmLockCouponIssueService;
    private final PessimisticCouponIssueService pessimisticCouponIssueService;
    private final OptimisticCouponIssueService optimisticCouponIssueService;
    private final ConditionalCouponIssueService conditionalCouponIssueService;
    private final RedisCouponIssueService redisCouponIssueService;

    @PostMapping("/coupons")
    @ResponseStatus(HttpStatus.CREATED)
    public CreateCouponResponse createCoupon(@RequestBody CreateCouponRequest request) {
        return experimentCouponService.create(request);
    }

    @PostMapping("/coupons/{couponId}/direct")
    public CouponIssueResponse issueDirect(
            @PathVariable("couponId") Long couponId,
            @Valid @RequestBody CouponIssueRequest request) {
        return directCouponIssueService.issue(couponId, request);
    }

    @PostMapping("/coupons/{couponId}/jvm-lock")
    public CouponIssueResponse issueJvmLock(
            @PathVariable("couponId") Long couponId,
            @Valid @RequestBody CouponIssueRequest request) {
        return jvmLockCouponIssueService.issue(couponId, request);
    }

    @PostMapping("/coupons/{couponId}/pessimistic")
    public CouponIssueResponse issuePessimistic(
            @PathVariable("couponId") Long couponId,
            @Valid @RequestBody CouponIssueRequest request) {
        return pessimisticCouponIssueService.issue(couponId, request);
    }

    @PostMapping("/coupons/{couponId}/optimistic")
    public CouponIssueResponse issueOptimistic(
            @PathVariable("couponId") Long couponId,
            @Valid @RequestBody CouponIssueRequest request) {
        return optimisticCouponIssueService.issue(couponId, request);
    }

    @PostMapping("/coupons/{couponId}/conditional")
    public CouponIssueResponse issueConditional(
            @PathVariable("couponId") Long couponId,
            @Valid @RequestBody CouponIssueRequest request) {
        return conditionalCouponIssueService.issue(couponId, request);
    }

    @PostMapping("/coupons/{couponId}/redis-lock")
    public CouponIssueResponse issueRedisLock(
            @PathVariable("couponId") Long couponId,
            @RequestParam(name = "runId", defaultValue = "r01") String runId,
            @Valid @RequestBody CouponIssueRequest request) {
        return redisCouponIssueService.issue(
                RedisCouponStrategy.REDIS_LOCK,
                couponId,
                runId,
                request);
    }

    @PostMapping("/coupons/{couponId}/redis-decr")
    public CouponIssueResponse issueRedisDecr(
            @PathVariable("couponId") Long couponId,
            @RequestParam(name = "runId", defaultValue = "r01") String runId,
            @Valid @RequestBody CouponIssueRequest request) {
        return redisCouponIssueService.issue(
                RedisCouponStrategy.REDIS_DECR,
                couponId,
                runId,
                request);
    }

    @PostMapping("/coupons/{couponId}/redis-lua")
    public CouponIssueResponse issueRedisLua(
            @PathVariable("couponId") Long couponId,
            @RequestParam(name = "runId", defaultValue = "r01") String runId,
            @Valid @RequestBody CouponIssueRequest request) {
        return redisCouponIssueService.issue(
                RedisCouponStrategy.REDIS_LUA,
                couponId,
                runId,
                request);
    }

    @PostMapping("/coupons/{couponId}/redis-watch")
    public CouponIssueResponse issueRedisWatch(
            @PathVariable("couponId") Long couponId,
            @RequestParam(name = "runId", defaultValue = "r01") String runId,
            @Valid @RequestBody CouponIssueRequest request) {
        return redisCouponIssueService.issue(
                RedisCouponStrategy.REDIS_WATCH,
                couponId,
                runId,
                request);
    }

    @PostMapping("/coupons/{couponId}/{strategy}/initialize")
    public CouponStatusResponse initializeRedis(
            @PathVariable("couponId") Long couponId,
            @PathVariable("strategy") String strategy,
            @RequestParam(name = "runId", defaultValue = "r01") String runId) {
        return redisCouponIssueService.initialize(strategy, couponId, runId);
    }

    @GetMapping("/coupons/{couponId}/{strategy}/status")
    public CouponStatusResponse getRedisStatus(
            @PathVariable("couponId") Long couponId,
            @PathVariable("strategy") String strategy,
            @RequestParam(name = "runId", defaultValue = "r01") String runId) {
        return redisCouponIssueService.getStatus(strategy, couponId, runId);
    }

    @PostMapping("/coupons/{couponId}/{strategy}/reset")
    public CouponStatusResponse resetRedis(
            @PathVariable("couponId") Long couponId,
            @PathVariable("strategy") String strategy,
            @RequestParam(name = "runId", defaultValue = "r01") String runId) {
        return redisCouponIssueService.reset(strategy, couponId, runId);
    }

    @GetMapping("/coupons/{couponId}/status")
    public CouponStatusResponse getStatus(@PathVariable("couponId") Long couponId) {
        return experimentCouponService.getStatus(couponId);
    }

    @PostMapping("/coupons/{couponId}/reset")
    public CouponStatusResponse reset(@PathVariable("couponId") Long couponId) {
        return experimentCouponService.reset(couponId);
    }
}
