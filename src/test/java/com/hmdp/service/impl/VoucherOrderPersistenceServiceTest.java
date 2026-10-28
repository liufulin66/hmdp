package com.hmdp.service.impl;

import com.hmdp.entity.VoucherOrder;
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
    private VoucherOrderPersistenceService service;

    @BeforeEach
    void setUp() {
        voucherOrderMapper = mock(VoucherOrderMapper.class);
        seckillVoucherMapper = mock(SeckillVoucherMapper.class);
        service = new VoucherOrderPersistenceService();
        ReflectionTestUtils.setField(service, "voucherOrderMapper", voucherOrderMapper);
        ReflectionTestUtils.setField(service, "seckillVoucherMapper", seckillVoucherMapper);
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
}
