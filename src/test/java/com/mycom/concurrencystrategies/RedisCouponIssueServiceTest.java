package com.mycom.concurrencystrategies;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.mycom.concurrencystrategies.coupon.dto.CouponIssueRequest;
import com.mycom.concurrencystrategies.coupon.dto.CouponIssueResponse;
import com.mycom.concurrencystrategies.coupon.dto.CouponIssueResult;
import com.mycom.concurrencystrategies.coupon.dto.CouponStatusResponse;
import com.mycom.concurrencystrategies.coupon.entity.Coupon;
import com.mycom.concurrencystrategies.coupon.entity.CouponStock;
import com.mycom.concurrencystrategies.coupon.repository.CouponRepository;
import com.mycom.concurrencystrategies.coupon.repository.CouponStockRepository;
import com.mycom.concurrencystrategies.coupon.service.ExperimentCouponService;
import com.mycom.concurrencystrategies.coupon.service.RedisCouponIssueService;
import com.mycom.concurrencystrategies.coupon.service.redis.RedisCouponStrategy;
import com.mycom.concurrencystrategies.coupon.service.redis.RedisReservationResult;
import com.mycom.concurrencystrategies.coupon.service.redis.RedisStockKeys;
import com.mycom.concurrencystrategies.coupon.service.redis.RedisStockReservationStrategy;
import com.mycom.concurrencystrategies.global.exception.CouponIssueException;
import com.mycom.concurrencystrategies.global.exception.InvalidExperimentRequestException;
import com.mycom.concurrencystrategies.issue.entity.CouponIssue;
import com.mycom.concurrencystrategies.issue.repository.CouponIssueRepository;
import com.mycom.concurrencystrategies.user.entity.User;
import com.mycom.concurrencystrategies.user.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class RedisCouponIssueServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private CouponStockRepository couponStockRepository;

    @Mock
    private CouponIssueRepository couponIssueRepository;

    @Mock
    private CouponRepository couponRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private ExperimentCouponService experimentCouponService;

    @Mock
    private RedisStockReservationStrategy reservationStrategy;

    private RedisCouponIssueService service;

    @BeforeEach
    void setUp() {
        service = new RedisCouponIssueService(
                redisTemplate,
                couponStockRepository,
                couponIssueRepository,
                couponRepository,
                userRepository,
                transactionManager,
                experimentCouponService,
                List.of(reservationStrategy));
    }

    @Test
    void initializesOnlyTheSelectedRunAndStrategyKey() {
        CouponStock stock = stock(10L, 100);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(couponStockRepository.findById(10L)).thenReturn(Optional.of(stock));
        when(couponIssueRepository.countByCoupon_Id(10L)).thenReturn(27L);

        CouponStatusResponse response = service.initialize("redis-lua", 10L, "r02");

        verify(valueOperations).set(
                "experiment:r02:redis-lua:coupon:10:stock",
                "73");
        verify(redisTemplate, never()).delete(anyString());
        assertThat(response.redisRemainingQuantity()).isEqualTo(73L);
        assertThat(response.issueCount()).isEqualTo(27L);
        assertThat(response.consistent()).isTrue();
    }

    @Test
    void rejectsUnsafeRunIdBeforeUsingRedis() {
        assertThatThrownBy(() -> service.initialize("redis-lua", 10L, "r01:other"))
                .isInstanceOf(InvalidExperimentRequestException.class);

        verifyNoInteractions(redisTemplate, couponStockRepository, couponIssueRepository);
    }

    @Test
    void issuesCouponAfterStrategyReservesStock() {
        prepareTransaction();
        prepareStrategy(RedisReservationResult.SUCCESS);
        CouponIssueRequest request = request(1L, "r01-redis-lua-user-1");
        when(couponRepository.getReferenceById(10L)).thenReturn(mock(Coupon.class));
        when(userRepository.getReferenceById(1L)).thenReturn(mock(User.class));

        CouponIssueResponse response = service.issue(
                RedisCouponStrategy.REDIS_LUA,
                10L,
                "r01",
                request);

        assertThat(response.result()).isEqualTo(CouponIssueResult.SUCCESS);
        verify(reservationStrategy).reserve(RedisStockKeys.of(
                "r01",
                RedisCouponStrategy.REDIS_LUA,
                10L));
        verify(couponIssueRepository).saveAndFlush(any(CouponIssue.class));
        verify(valueOperations, never()).increment(anyString());
    }

    @Test
    void compensatesTheExactStrategyKeyWhenDatabaseInsertRollsBack() {
        prepareTransaction();
        prepareStrategy(RedisReservationResult.SUCCESS);
        CouponIssueRequest request = request(1L, "r01-redis-lua-user-1");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(couponRepository.getReferenceById(10L)).thenReturn(mock(Coupon.class));
        when(userRepository.getReferenceById(1L)).thenReturn(mock(User.class));
        when(couponIssueRepository.saveAndFlush(any(CouponIssue.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate",
                        new RuntimeException("Duplicate entry for key 'uq_request_id'")));

        assertThatThrownBy(() -> service.issue(
                RedisCouponStrategy.REDIS_LUA,
                10L,
                "r01",
                request))
                .isInstanceOfSatisfying(CouponIssueException.class, exception ->
                        assertThat(exception.getResult())
                                .isEqualTo(CouponIssueResult.DUPLICATE_REQUEST));

        verify(valueOperations).increment(
                "experiment:r01:redis-lua:coupon:10:stock");
        verify(transactionManager).rollback(any());
    }

    @Test
    void rejectsIssueWhenStrategyStockIsNotInitialized() {
        prepareTransaction();
        prepareStrategy(RedisReservationResult.NOT_INITIALIZED);
        CouponIssueRequest request = request(1L, "r01-redis-lua-user-1");
        when(couponStockRepository.existsById(10L)).thenReturn(true);

        assertThatThrownBy(() -> service.issue(
                RedisCouponStrategy.REDIS_LUA,
                10L,
                "r01",
                request))
                .isInstanceOfSatisfying(CouponIssueException.class, exception ->
                        assertThat(exception.getResult())
                                .isEqualTo(CouponIssueResult.REDIS_NOT_INITIALIZED));

        verify(couponIssueRepository, never()).saveAndFlush(any());
        verify(valueOperations, never()).increment(anyString());
    }

    @Test
    void rejectsIssueWhenStrategyStockIsSoldOut() {
        prepareTransaction();
        prepareStrategy(RedisReservationResult.SOLD_OUT);
        CouponIssueRequest request = request(1L, "r01-redis-lua-user-1");

        assertThatThrownBy(() -> service.issue(
                RedisCouponStrategy.REDIS_LUA,
                10L,
                "r01",
                request))
                .isInstanceOfSatisfying(CouponIssueException.class, exception ->
                        assertThat(exception.getResult()).isEqualTo(CouponIssueResult.SOLD_OUT));

        verify(couponIssueRepository, never()).saveAndFlush(any());
        verify(valueOperations, never()).increment(anyString());
    }

    private void prepareTransaction() {
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
    }

    private void prepareStrategy(RedisReservationResult result) {
        when(reservationStrategy.strategy()).thenReturn(RedisCouponStrategy.REDIS_LUA);
        when(reservationStrategy.reserve(any(RedisStockKeys.class))).thenReturn(result);
    }

    private CouponStock stock(Long couponId, int quantity) {
        return CouponStock.builder()
                .couponId(couponId)
                .quantity(quantity)
                .build();
    }

    private CouponIssueRequest request(Long userId, String requestId) {
        return CouponIssueRequest.builder()
                .userId(userId)
                .requestId(requestId)
                .build();
    }
}
