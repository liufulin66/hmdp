package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.LocalDateTime;

import static com.hmdp.utils.RedisConstants.SECKILL_META_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class VoucherServiceImplTest {
    private ValueOperations<String, String> values;
    private HashOperations<String, Object, Object> hash;
    private ISeckillVoucherService seckillVoucherService;
    private VoucherServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        hash = mock(HashOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForHash()).thenReturn(hash);
        seckillVoucherService = mock(ISeckillVoucherService.class);
        service = spy(new VoucherServiceImpl());
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(service, "seckillVoucherService", seckillVoucherService);
    }

    @Test
    void normalVoucherForcesTypeAndStatus() {
        Voucher voucher = normalVoucher();
        stubSaveWithId(10L);

        Result result = service.addVoucher(voucher);

        assertTrue(result.getSuccess());
        assertEquals(10L, result.getData());
        assertEquals(Integer.valueOf(0), voucher.getType());
        assertEquals(Integer.valueOf(1), voucher.getStatus());
    }

    @Test
    void normalVoucherRejectsInvalidAmountWithoutSaving() {
        Voucher voucher = normalVoucher().setPayValue(300L);

        Result result = service.addVoucher(voucher);

        assertFalse(result.getSuccess());
        assertEquals("支付金额不能大于抵扣金额", result.getErrorMsg());
        verify(service, never()).save(any());
    }

    @Test
    void seckillVoucherForcesTypeAndPreheatsStock() {
        Voucher voucher = seckillVoucher();
        stubSaveWithId(20L);
        when(seckillVoucherService.save(any(SeckillVoucher.class))).thenReturn(true);

        Result result = service.addSeckillVoucher(voucher);

        assertTrue(result.getSuccess());
        assertEquals(20L, result.getData());
        assertEquals(Integer.valueOf(1), voucher.getType());
        assertEquals(Integer.valueOf(1), voucher.getStatus());
        verify(seckillVoucherService).save(argThat(seckill ->
                seckill.getVoucherId().equals(20L) && seckill.getStock().equals(10)));
        verify(values).set(SECKILL_STOCK_KEY + 20L, "10");
        verify(hash).putAll(eq(SECKILL_META_KEY + 20L), anyMap());
    }

    @Test
    void seckillVoucherRejectsZeroStockWithoutSaving() {
        Voucher voucher = seckillVoucher().setStock(0);

        Result result = service.addSeckillVoucher(voucher);

        assertFalse(result.getSuccess());
        assertEquals("秒杀库存必须大于0", result.getErrorMsg());
        verify(service, never()).save(any());
        verifyNoInteractions(seckillVoucherService);
    }

    @Test
    void seckillVoucherRejectsInvalidTimeRange() {
        Voucher voucher = seckillVoucher().setBeginTime(LocalDateTime.of(2026, 12, 1, 0, 0));

        Result result = service.addSeckillVoucher(voucher);

        assertFalse(result.getSuccess());
        assertEquals("秒杀开始时间必须早于结束时间", result.getErrorMsg());
        verify(service, never()).save(any());
    }

    @Test
    void redisFailureDoesNotFailVoucherCreation() {
        Voucher voucher = seckillVoucher();
        stubSaveWithId(30L);
        when(seckillVoucherService.save(any(SeckillVoucher.class))).thenReturn(true);
        doThrow(new DataAccessResourceFailureException("redis unavailable"))
                .when(values).set(anyString(), anyString());

        Result result = service.addSeckillVoucher(voucher);

        assertTrue(result.getSuccess());
        assertEquals(30L, result.getData());
    }

    @Test
    void seckillStockIsPreheatedOnlyAfterCommit() {
        TestTransactionManager manager = new TestTransactionManager();
        Voucher voucher = seckillVoucher();
        stubSaveWithId(40L);
        when(seckillVoucherService.save(any(SeckillVoucher.class))).thenReturn(true);
        doAnswer(invocation -> {
            assertTrue(manager.committed);
            return null;
        }).when(values).set(SECKILL_STOCK_KEY + 40L, "10");
        doAnswer(invocation -> {
            assertTrue(manager.committed);
            return null;
        }).when(hash).putAll(eq(SECKILL_META_KEY + 40L), anyMap());

        Result result = transactionalService(manager).addSeckillVoucher(voucher);

        assertTrue(result.getSuccess());
        verify(values).set(SECKILL_STOCK_KEY + 40L, "10");
        verify(hash).putAll(eq(SECKILL_META_KEY + 40L), anyMap());
    }

    private void stubSaveWithId(long id) {
        doAnswer(invocation -> {
            ((Voucher) invocation.getArgument(0)).setId(id);
            return true;
        }).when(service).save(any(Voucher.class));
    }

    private IVoucherService transactionalService(TestTransactionManager manager) {
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory factory = new ProxyFactory(service);
        factory.addAdvice(interceptor);
        return (IVoucherService) factory.getProxy();
    }

    private Voucher normalVoucher() {
        return new Voucher()
                .setShopId(1L)
                .setTitle("50元代金券")
                .setPayValue(100L)
                .setActualValue(200L);
    }

    private Voucher seckillVoucher() {
        return normalVoucher()
                .setStock(10)
                .setBeginTime(LocalDateTime.of(2026, 11, 1, 0, 0))
                .setEndTime(LocalDateTime.of(2026, 11, 30, 23, 59));
    }

    private static class TestTransactionManager extends AbstractPlatformTransactionManager {
        private boolean committed;

        @Override
        protected Object doGetTransaction() { return new Object(); }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) { }

        @Override
        protected void doCommit(DefaultTransactionStatus status) { committed = true; }

        @Override
        protected void doRollback(DefaultTransactionStatus status) { }
    }
}
