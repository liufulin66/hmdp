package com.hmdp.mq;

import com.hmdp.service.impl.VoucherOrderPersistenceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.hmdp.utils.RedisConstants.SECKILL_CONSUMER_GROUP;
import static com.hmdp.utils.RedisConstants.SECKILL_CONSUMER_NAME;
import static com.hmdp.utils.RedisConstants.SECKILL_DEAD_LETTER_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STREAM_KEY;

/**
 * 秒杀订单消费者：从 Redis Stream 读取订单消息，交给事务方法落库，成功后 ACK。
 *
 * <p>失败消息不 ACK，会留在 pending 列表；消费者每 30 秒重试一次，
 * 超过重试次数后转入死信 Stream，避免永久阻塞。</p>
 */
@Slf4j
@Component
public class VoucherOrderConsumer implements SmartLifecycle {

    private static final int MAX_RETRIES = 3;
    private static final long PENDING_CHECK_INTERVAL_MILLIS = 30_000L;

    private final StringRedisTemplate stringRedisTemplate;
    private final VoucherOrderPersistenceService persistenceService;
    private final StreamOperations<String, String, String> streamOps;
    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, Integer> attempts = new ConcurrentHashMap<>();

    public VoucherOrderConsumer(StringRedisTemplate stringRedisTemplate,
                                VoucherOrderPersistenceService persistenceService) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.persistenceService = persistenceService;
        this.streamOps = stringRedisTemplate.opsForStream();
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "seckill-order-consumer");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            executor.submit(this::consumeLoop);
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            executor.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private void consumeLoop() {
        if (!ensureGroup()) {
            return;
        }
        long lastPendingCheck = 0L;
        while (running.get()) {
            try {
                long now = System.currentTimeMillis();
                if (now - lastPendingCheck >= PENDING_CHECK_INTERVAL_MILLIS) {
                    recoverPending();
                    lastPendingCheck = now;
                }
                List<MapRecord<String, String, String>> records = streamOps.read(
                        Consumer.from(SECKILL_CONSUMER_GROUP, SECKILL_CONSUMER_NAME),
                        StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                        StreamOffset.create(SECKILL_STREAM_KEY, ReadOffset.lastConsumed()));
                if (records != null) {
                    for (MapRecord<String, String, String> record : records) {
                        process(record);
                    }
                }
            } catch (RuntimeException e) {
                if (!running.get()) {
                    break;
                }
                log.warn("读取秒杀订单消息失败，1 秒后重试", e);
                sleepQuietly();
                if (!ensureGroup()) {
                    break;
                }
            }
        }
    }

    /**
     * XGROUP CREATE ... MKSTREAM：Stream 或消费组不存在时自动创建。
     * 组已存在时 Redis 返回 BUSYGROUP，按成功处理。
     */
    private boolean ensureGroup() {
        byte[] streamKey = serialize(SECKILL_STREAM_KEY);
        while (running.get()) {
            try {
                stringRedisTemplate.execute((RedisCallback<String>) connection ->
                        connection.xGroupCreate(
                                streamKey,
                                SECKILL_CONSUMER_GROUP,
                                ReadOffset.from("0"),
                                true));
                return true;
            } catch (RuntimeException e) {
                if (isBusyGroup(e)) {
                    return true;
                }
                log.warn("创建秒杀订单消费组失败，1 秒后重试", e);
                sleepQuietly();
            }
        }
        return false;
    }

    /**
     * 读取当前消费者 pending 列表中的消息并重试。
     * 单实例使用固定消费者名，因此重启后也能恢复未 ACK 的消息。
     */
    private void recoverPending() {
        try {
            List<MapRecord<String, String, String>> records = streamOps.read(
                    Consumer.from(SECKILL_CONSUMER_GROUP, SECKILL_CONSUMER_NAME),
                    StreamReadOptions.empty().count(10),
                    StreamOffset.create(SECKILL_STREAM_KEY, ReadOffset.from("0")));
            if (records != null) {
                for (MapRecord<String, String, String> record : records) {
                    process(record);
                }
            }
        } catch (RuntimeException e) {
            log.warn("重试 pending 秒杀订单消息失败", e);
        }
    }

    void process(MapRecord<String, String, String> record) {
        String recordId = record.getId().getValue();
        Map<String, String> value = record.getValue();
        long userId;
        long voucherId;
        long orderId;
        try {
            userId = Long.parseLong(value.get("userId"));
            voucherId = Long.parseLong(value.get("voucherId"));
            orderId = Long.parseLong(value.get("id"));
        } catch (RuntimeException e) {
            log.error("秒杀订单消息格式错误，recordId={}，message={}", recordId, value, e);
            if (deadLetter(record)) {
                acknowledge(recordId);
            }
            return;
        }
        try {
            persistenceService.persistVoucherOrder(orderId, userId, voucherId);
        } catch (DuplicateKeyException e) {
            // 订单已存在，说明消息被重复投递；唯一索引保证了幂等
            log.warn("秒杀订单已存在，按幂等处理，recordId={}", recordId);
            acknowledge(recordId);
            attempts.remove(recordId);
            return;
        } catch (Exception e) {
            int count = attempts.merge(recordId, 1, Integer::sum);
            log.error("创建秒杀订单失败，recordId={}，第 {} 次", recordId, count, e);
            if (count >= MAX_RETRIES && deadLetter(record)) {
                acknowledge(recordId);
                attempts.remove(recordId);
            }
            return;
        }
        acknowledge(recordId);
        attempts.remove(recordId);
    }

    private void acknowledge(String recordId) {
        try {
            streamOps.acknowledge(SECKILL_STREAM_KEY, SECKILL_CONSUMER_GROUP, recordId);
        } catch (RuntimeException e) {
            // ACK 失败时消息会留在 pending，重复投递由唯一索引兜底
            log.warn("秒杀订单消息 ACK 失败，recordId={}", recordId, e);
        }
    }

    private boolean deadLetter(MapRecord<String, String, String> record) {
        try {
            streamOps.add(MapRecord.create(SECKILL_DEAD_LETTER_KEY, record.getValue()));
            log.error("秒杀订单消息转入死信 Stream，recordId={}", record.getId().getValue());
            return true;
        } catch (RuntimeException e) {
            log.error("写入死信 Stream 失败，recordId={}", record.getId().getValue(), e);
            return false;
        }
    }

    private boolean isBusyGroup(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains("BUSYGROUP")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private byte[] serialize(String value) {
        return stringRedisTemplate.getStringSerializer().serialize(value);
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
