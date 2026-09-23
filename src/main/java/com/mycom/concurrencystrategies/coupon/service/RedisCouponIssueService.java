package com.mycom.concurrencystrategies.coupon.service;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.mycom.concurrencystrategies.coupon.dto.CouponIssueRequest;
import com.mycom.concurrencystrategies.coupon.dto.CouponIssueResponse;
import com.mycom.concurrencystrategies.coupon.dto.CouponIssueResult;
import com.mycom.concurrencystrategies.coupon.dto.CouponStatusResponse;
import com.mycom.concurrencystrategies.coupon.entity.CouponStock;
import com.mycom.concurrencystrategies.coupon.repository.CouponRepository;
import com.mycom.concurrencystrategies.coupon.repository.CouponStockRepository;
import com.mycom.concurrencystrategies.coupon.service.redis.RedisCouponStrategy;
import com.mycom.concurrencystrategies.coupon.service.redis.RedisReservationResult;
import com.mycom.concurrencystrategies.coupon.service.redis.RedisStockKeys;
import com.mycom.concurrencystrategies.coupon.service.redis.RedisStockReservationStrategy;
import com.mycom.concurrencystrategies.global.exception.CouponIssueException;
import com.mycom.concurrencystrategies.global.exception.CouponNotFoundException;
import com.mycom.concurrencystrategies.global.exception.InvalidExperimentRequestException;
import com.mycom.concurrencystrategies.issue.entity.CouponIssue;
import com.mycom.concurrencystrategies.issue.repository.CouponIssueRepository;
import com.mycom.concurrencystrategies.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RedisCouponIssueService {

    private static final Logger log = LoggerFactory.getLogger(RedisCouponIssueService.class);
    private static final Pattern RUN_ID_PATTERN =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,31}");

    private final StringRedisTemplate redisTemplate;
    private final CouponStockRepository couponStockRepository;
    private final CouponIssueRepository couponIssueRepository;
    private final CouponRepository couponRepository;
    private final UserRepository userRepository;
    private final PlatformTransactionManager transactionManager;
    private final ExperimentCouponService experimentCouponService;
    private final List<RedisStockReservationStrategy> reservationStrategies;

    @Transactional(readOnly = true)
    public CouponStatusResponse initialize(
            String strategyPath,
            Long couponId,
            String runId) {
        RedisCouponStrategy strategy = RedisCouponStrategy.fromPath(strategyPath);
        RedisStockKeys keys = keys(runId, strategy, couponId);
        CouponStock stock = findStock(couponId);
        long issueCount = couponIssueRepository.countByCoupon_Id(couponId);
        if (issueCount > stock.getTotalQuantity()) {
            throw internalError(
                    couponId,
                    null,
                    "Issue count exceeds total coupon quantity");
        }

        long redisRemainingQuantity = stock.getTotalQuantity() - issueCount;
        clearLockKey(strategy, keys);
        redisTemplate.opsForValue().set(
                keys.stock(),
                Long.toString(redisRemainingQuantity));

        return status(stock, issueCount, redisRemainingQuantity);
    }

    public CouponIssueResponse issue(
            RedisCouponStrategy strategy,
            Long couponId,
            String runId,
            CouponIssueRequest request) {
        RedisStockKeys keys = keys(runId, strategy, couponId);
        RedisStockReservationStrategy reservationStrategy = reservationStrategy(strategy);
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        AtomicBoolean reserved = new AtomicBoolean(false);

        try {
            CouponIssueResponse response = transactionTemplate.execute(status -> {
                rejectDuplicate(couponId, request);
                RedisReservationResult result = reservationStrategy.reserve(keys);
                handleReservationResult(result, couponId, request);
                reserved.set(true);
                saveIssue(couponId, request);
                return CouponIssueResponse.success(couponId, request);
            });
            reserved.set(false);
            return response;
        } catch (RuntimeException exception) {
            if (reserved.getAndSet(false)) {
                compensateStock(keys.stock(), couponId, strategy, exception);
            }
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public CouponStatusResponse getStatus(
            String strategyPath,
            Long couponId,
            String runId) {
        RedisCouponStrategy strategy = RedisCouponStrategy.fromPath(strategyPath);
        RedisStockKeys keys = keys(runId, strategy, couponId);
        CouponStock stock = findStock(couponId);
        long issueCount = couponIssueRepository.countByCoupon_Id(couponId);
        Long redisRemainingQuantity = readStock(keys.stock(), couponId);
        return status(stock, issueCount, redisRemainingQuantity);
    }

    public CouponStatusResponse reset(
            String strategyPath,
            Long couponId,
            String runId) {
        RedisCouponStrategy strategy = RedisCouponStrategy.fromPath(strategyPath);
        RedisStockKeys keys = keys(runId, strategy, couponId);

        experimentCouponService.reset(couponId);
        redisTemplate.delete(keys.stock());
        clearLockKey(strategy, keys);
        return initialize(strategy.path(), couponId, runId);
    }

    private void handleReservationResult(
            RedisReservationResult result,
            Long couponId,
            CouponIssueRequest request) {
        switch (result) {
            case SUCCESS -> {
                return;
            }
            case SOLD_OUT -> throw CouponIssueException.soldOut(couponId, request);
            case NOT_INITIALIZED -> {
                if (!couponStockRepository.existsById(couponId)) {
                    throw couponNotFound(couponId, request);
                }
                throw CouponIssueException.redisNotInitialized(couponId, request);
            }
            case RETRY_EXHAUSTED -> throw internalError(
                    couponId,
                    request,
                    "Redis reservation retry limit exceeded");
            case ERROR -> throw internalError(
                    couponId,
                    request,
                    "Redis stock reservation failed");
        }
    }

    private RedisStockReservationStrategy reservationStrategy(RedisCouponStrategy strategy) {
        return reservationStrategies.stream()
                .filter(candidate -> candidate.strategy() == strategy)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Redis reservation strategy is not registered: " + strategy));
    }

    private void rejectDuplicate(Long couponId, CouponIssueRequest request) {
        if (couponIssueRepository.existsByRequestId(request.requestId())) {
            throw CouponIssueException.duplicateRequest(couponId, request);
        }
        if (couponIssueRepository.existsByCoupon_IdAndUser_Id(couponId, request.userId())) {
            throw CouponIssueException.duplicateUser(couponId, request);
        }
    }

    private void saveIssue(Long couponId, CouponIssueRequest request) {
        try {
            couponIssueRepository.saveAndFlush(CouponIssue.builder()
                    .coupon(couponRepository.getReferenceById(couponId))
                    .user(userRepository.getReferenceById(request.userId()))
                    .requestId(request.requestId())
                    .build());
        } catch (DataIntegrityViolationException exception) {
            throw CouponIssueException.translateDataIntegrityViolation(
                    couponId,
                    request,
                    exception);
        }
    }

    private void compensateStock(
            String stockKey,
            Long couponId,
            RedisCouponStrategy strategy,
            RuntimeException originalException) {
        try {
            redisTemplate.opsForValue().increment(stockKey);
        } catch (RuntimeException compensationException) {
            log.error(
                    "Failed to compensate Redis stock. strategy={}, couponId={}, stockKey={}",
                    strategy,
                    couponId,
                    stockKey,
                    compensationException);
            originalException.addSuppressed(compensationException);
        }
    }

    private CouponStatusResponse status(
            CouponStock stock,
            long issueCount,
            Long redisRemainingQuantity) {
        boolean consistent = redisRemainingQuantity != null
                && redisRemainingQuantity >= 0
                && redisRemainingQuantity + issueCount == stock.getTotalQuantity();

        return CouponStatusResponse.builder()
                .couponId(stock.getCouponId())
                .totalQuantity(stock.getTotalQuantity())
                .dbRemainingQuantity(stock.getRemainingQuantity())
                .redisRemainingQuantity(redisRemainingQuantity)
                .issueCount(issueCount)
                .consistent(consistent)
                .build();
    }

    private Long readStock(String stockKey, Long couponId) {
        String value = redisTemplate.opsForValue().get(stockKey);
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            throw internalError(
                    couponId,
                    null,
                    "Redis stock is not a valid number",
                    exception);
        }
    }

    private CouponStock findStock(Long couponId) {
        return couponStockRepository.findById(couponId)
                .orElseThrow(() -> new CouponNotFoundException(couponId));
    }

    private RedisStockKeys keys(
            String runId,
            RedisCouponStrategy strategy,
            Long couponId) {
        if (runId == null || !RUN_ID_PATTERN.matcher(runId).matches()) {
            throw new InvalidExperimentRequestException(
                    "runId must match " + RUN_ID_PATTERN.pattern());
        }
        return RedisStockKeys.of(runId, strategy, couponId);
    }

    private void clearLockKey(RedisCouponStrategy strategy, RedisStockKeys keys) {
        if (strategy == RedisCouponStrategy.REDIS_LOCK) {
            redisTemplate.delete(keys.lock());
        }
    }

    private CouponIssueException couponNotFound(
            Long couponId,
            CouponIssueRequest request) {
        return new CouponIssueException(
                couponId,
                request.userId(),
                request.requestId(),
                CouponIssueResult.COUPON_NOT_FOUND,
                "Coupon stock was not found");
    }

    private CouponIssueException internalError(
            Long couponId,
            CouponIssueRequest request,
            String message) {
        return internalError(couponId, request, message, null);
    }

    private CouponIssueException internalError(
            Long couponId,
            CouponIssueRequest request,
            String message,
            Throwable cause) {
        Long userId = request == null ? null : request.userId();
        String requestId = request == null ? null : request.requestId();
        return new CouponIssueException(
                couponId,
                userId,
                requestId,
                CouponIssueResult.INTERNAL_ERROR,
                message,
                cause);
    }
}
