package com.mycom.concurrencystrategies.coupon.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.mycom.concurrencystrategies.coupon.dto.CouponIssueRequest;
import com.mycom.concurrencystrategies.coupon.dto.CouponIssueResponse;
import com.mycom.concurrencystrategies.coupon.dto.CouponIssueResult;
import com.mycom.concurrencystrategies.coupon.entity.CouponStock;
import com.mycom.concurrencystrategies.coupon.repository.CouponRepository;
import com.mycom.concurrencystrategies.coupon.repository.CouponStockRepository;
import com.mycom.concurrencystrategies.global.exception.CouponIssueException;
import com.mycom.concurrencystrategies.issue.entity.CouponIssue;
import com.mycom.concurrencystrategies.issue.repository.CouponIssueRepository;
import com.mycom.concurrencystrategies.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class OptimisticCouponIssueService {

    private static final int MAX_RETRY = 20;

    private final CouponStockRepository couponStockRepository;
    private final CouponIssueRepository couponIssueRepository;
    private final CouponRepository couponRepository;
    private final UserRepository userRepository;
    private final PlatformTransactionManager transactionManager;

    public CouponIssueResponse issue(Long couponId, CouponIssueRequest request) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        for (int attempt = 0; attempt < MAX_RETRY; attempt++) {
            CouponIssueResponse response = transactionTemplate.execute(
                    status -> issueOnce(couponId, request));
            if (response != null) {
                return response;
            }
        }

        throw retryExceeded(couponId, request);
    }

    private CouponIssueResponse issueOnce(Long couponId, CouponIssueRequest request) {
        rejectDuplicate(couponId, request);

        CouponStock stock = couponStockRepository.findById(couponId)
                .orElseThrow(() -> couponNotFound(couponId, request));
        if (stock.getRemainingQuantity() <= 0) {
            throw CouponIssueException.soldOut(couponId, request);
        }

        int affectedRows = couponStockRepository.issueWithOptimisticLock(
                couponId,
                stock.getVersion());
        if (affectedRows == 0) {
            return null;
        }

        saveIssue(couponId, request);
        return CouponIssueResponse.success(couponId, request);
    }

    private void rejectDuplicate(Long couponId, CouponIssueRequest request) {
        if (couponIssueRepository.existsByRequestId(request.requestId())) {
            throw CouponIssueException.duplicateRequest(couponId, request);
        }
        if (couponIssueRepository.existsByCoupon_IdAndUser_Id(couponId, request.userId())) {
            throw CouponIssueException.duplicateUser(couponId, request);
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

    private CouponIssueException retryExceeded(
            Long couponId,
            CouponIssueRequest request) {
        return new CouponIssueException(
                couponId,
                request.userId(),
                request.requestId(),
                CouponIssueResult.INTERNAL_ERROR,
                "Optimistic lock retry limit exceeded");
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
}
