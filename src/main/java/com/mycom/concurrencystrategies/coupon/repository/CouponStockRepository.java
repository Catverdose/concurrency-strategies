package com.mycom.concurrencystrategies.coupon.repository;

import java.util.Optional;

import com.mycom.concurrencystrategies.coupon.entity.CouponStock;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CouponStockRepository extends JpaRepository<CouponStock, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select stock from CouponStock stock where stock.couponId = :couponId")
    Optional<CouponStock> findByIdWithPessimisticLock(@Param("couponId") Long couponId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update CouponStock stock
               set stock.issuedQuantity = stock.issuedQuantity + 1,
                   stock.remainingQuantity = stock.remainingQuantity - 1,
                   stock.version = stock.version + 1,
                   stock.updatedAt = CURRENT_TIMESTAMP
             where stock.couponId = :couponId
               and stock.version = :version
               and stock.remainingQuantity > 0
            """)
    int issueWithOptimisticLock(
            @Param("couponId") Long couponId,
            @Param("version") Long version);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update CouponStock stock
               set stock.issuedQuantity = stock.issuedQuantity + 1,
                   stock.remainingQuantity = stock.remainingQuantity - 1,
                   stock.updatedAt = CURRENT_TIMESTAMP
             where stock.couponId = :couponId
               and stock.remainingQuantity > 0
            """)
    int issueWithConditionalUpdate(@Param("couponId") Long couponId);
}
