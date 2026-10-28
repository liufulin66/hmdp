package com.hmdp.service.impl;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.entity.VoucherOrderTask;
import com.hmdp.mapper.VoucherOrderTaskMapper;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class VoucherOrderPersistenceServiceTest {
    private VoucherOrderMapper voucherOrderMapper;
    private SeckillVoucherMapper seckillVoucherMapper;
    private VoucherOrderTaskMapper taskMapper;
    private VoucherOrderTask task;
    private VoucherOrderPersistenceService service;

    @BeforeEach
    void setUp() {
        voucherOrderMapper = mock(VoucherOrderMapper.class);
        seckillVoucherMapper = mock(SeckillVoucherMapper.class);
        taskMapper = mock(VoucherOrderTaskMapper.class);
        task = new VoucherOrderTask();
        task.setOrderId(100L);
        task.setUserId(1L);
        task.setVoucherId(2L);
        task.setStatus(VoucherOrderTask.PROCESSING);
        task.setRetryCount(0);
        task.setRestoreQualification(true);
        when(taskMapper.lockTask(100L)).thenReturn(task);
        when(taskMapper.updateById(any(VoucherOrderTask.class))).thenReturn(1);
        service = new VoucherOrderPersistenceService();
        ReflectionTestUtils.setField(service, "voucherOrderMapper", voucherOrderMapper);
        ReflectionTestUtils.setField(service, "seckillVoucherMapper", seckillVoucherMapper);
        ReflectionTestUtils.setField(service, "taskMapper", taskMapper);
    }

    @Test
    void insertsOrderThenDeductsDatabaseStock() {
        when(voucherOrderMapper.insert(any(VoucherOrder.class))).thenReturn(1);
        when(seckillVoucherMapper.deductStock(2L)).thenReturn(1);

        service.persistVoucherOrder(100L, 1L, 2L);

        ArgumentCaptor<VoucherOrder> captor = ArgumentCaptor.forClass(VoucherOrder.class);
        verify(voucherOrderMapper).insert(captor.capture());
        VoucherOrder order = captor.getValue();
        assertEquals(100L, order.getId());
        assertEquals(1L, order.getUserId());
        assertEquals(2L, order.getVoucherId());
        assertEquals(Integer.valueOf(1), order.getStatus());
        assertEquals(Integer.valueOf(1), order.getPayType());
        verify(seckillVoucherMapper).deductStock(2L);
        assertEquals(VoucherOrderTask.SUCCESS, task.getStatus());
    }

    @Test
    void duplicateOrderPropagatesAndDoesNotDeductStock() {
        when(voucherOrderMapper.insert(any(VoucherOrder.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));

        assertThrows(DuplicateKeyException.class,
                () -> service.persistVoucherOrder(100L, 1L, 2L));

        verify(seckillVoucherMapper, never()).deductStock(anyLong());
    }

    @Test
    void zeroDatabaseStockRollsBackByThrowing() {
        when(voucherOrderMapper.insert(any(VoucherOrder.class))).thenReturn(1);
        when(seckillVoucherMapper.deductStock(2L)).thenReturn(0);

        assertThrows(IllegalStateException.class,
                () -> service.persistVoucherOrder(100L, 1L, 2L));
    }

    @Test
    void failedInsertDoesNotDeductStock() {
        when(voucherOrderMapper.insert(any(VoucherOrder.class))).thenReturn(0);

        assertThrows(IllegalStateException.class,
                () -> service.persistVoucherOrder(100L, 1L, 2L));

        verify(seckillVoucherMapper, never()).deductStock(anyLong());
    }

    @Test
    void thirdConfirmedFailureFencesOffFuturePersistence() {
        service.recordFailure(100L, 1L, 2L);
        service.recordFailure(100L, 1L, 2L);
        assertEquals(VoucherOrderTask.PROCESSING, task.getStatus());
        service.recordFailure(100L, 1L, 2L);
        assertEquals(VoucherOrderTask.COMPENSATING, task.getStatus());
        assertSame(task, service.persistVoucherOrder(100L, 1L, 2L));
        verify(voucherOrderMapper, never()).insert(any(VoucherOrder.class));
    }

    @Test
    void uncertainCommitIsReconciledAsSuccessWithoutCompensation() {
        VoucherOrder existing = new VoucherOrder();
        existing.setId(100L);
        existing.setUserId(1L);
        existing.setVoucherId(2L);
        when(voucherOrderMapper.selectById(100L)).thenReturn(existing);
        assertEquals(VoucherOrderTask.SUCCESS, service.recordFailure(100L, 1L, 2L).getStatus());
        assertEquals(0, task.getRetryCount());
    }

    @Test
    void anotherSuccessfulPurchaseKeepsQualificationBlocked() {
        VoucherOrder existing = new VoucherOrder();
        existing.setId(99L);
        when(voucherOrderMapper.selectPurchase(1L, 2L)).thenReturn(existing);
        service.recordFailure(100L, 1L, 2L);
        assertEquals(VoucherOrderTask.COMPENSATING, task.getStatus());
        assertFalse(task.getRestoreQualification());
    }

    @Test
    void completedFailureCannotBeResurrectedByOldMessage() {
        task.setStatus(VoucherOrderTask.COMPENSATING);
        service.completeCompensation(100L);
        assertEquals(VoucherOrderTask.FAILED, task.getStatus());
        service.persistVoucherOrder(100L, 1L, 2L);
        verifyNoInteractions(voucherOrderMapper, seckillVoucherMapper);
    }

    @Test
    void refusesMessageWithDifferentOwner() {
        assertThrows(IllegalStateException.class, () -> service.persistVoucherOrder(100L, 9L, 2L));
        verifyNoInteractions(voucherOrderMapper, seckillVoucherMapper);
    }
}
