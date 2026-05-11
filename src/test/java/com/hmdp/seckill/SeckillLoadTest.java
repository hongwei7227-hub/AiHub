package com.hmdp.seckill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan E 秒杀压测主体：N 并发抢同一张库存=200 的券。
 *
 * <p>验证：
 * <ul>
 *   <li>0 超卖（success 数 = stock）
 *   <li>0 重复（user_id 唯一）
 *   <li>Lua 脚本拒绝数 = N - stock
 *   <li>Redis 库存清零
 *   <li>RocketMQ 消费完成
 *   <li>P50 / P95 / P99 响应时间统计
 * </ul>
 *
 * <p>前置：
 * <ol>
 *   <li>controller @SlidingWindowLimit 临时注释
 *   <li>SeckillTestSeed.createSeckillVoucher() 已跑（target/test-classes/seckill-voucher-id.txt 存在）
 *   <li>SeckillTestSeed.seedTokens() 已跑（target/test-classes/seckill-tokens.csv 存在）
 *   <li>port 8083 空闲（业务 app 没在跑）
 *   <li>Redis + MySQL + RocketMQ docker 全部 UP
 * </ol>
 */
@Slf4j
@Disabled("Manual stress test. See class javadoc for prerequisites.")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = "ai.agent.bootstrap.enabled=false"
)
class SeckillLoadTest {

    private static final Path VOUCHER_ID_FILE = Path.of("target", "test-classes", "seckill-voucher-id.txt");
    private static final Path TOKENS_FILE = Path.of("target", "test-classes", "seckill-tokens.csv");
    private static final String BASE_URL = "http://localhost:8083";
    private static final int CONCURRENT_REQUESTS = 500;       // 总请求数
    private static final int THREAD_POOL_SIZE = 50;           // 物理并发线程数
    private static final int MQ_CONSUMER_WAIT_SECONDS = 30;   // 等 MQ 消费完成最多 30s

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void seckillStormShouldNotOversell() throws Exception {
        // ===== 1. 准备 =====
        long voucherId = Long.parseLong(Files.readString(VOUCHER_ID_FILE, StandardCharsets.UTF_8).trim());
        List<TokenUser> tokens = readTokens();
        if (tokens.size() < CONCURRENT_REQUESTS) {
            throw new IllegalStateException("Not enough tokens: have " + tokens.size()
                    + ", need " + CONCURRENT_REQUESTS + "; rerun SeckillTestSeed.seedTokens()");
        }

        // 读初始库存
        int initialStock = readRedisStock(voucherId);
        log.info("=========== Plan E Seckill Storm ===========");
        log.info("voucherId={}, initial Redis stock={}, requests={}, parallelism={}",
                voucherId, initialStock, CONCURRENT_REQUESTS, THREAD_POOL_SIZE);

        // ===== 2. 启动 N worker =====
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CONCURRENT_REQUESTS);
        AtomicInteger okCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();
        AtomicInteger errorCount = new AtomicInteger();
        ConcurrentHashMap<String, AtomicInteger> errorMsgBuckets = new ConcurrentHashMap<>();
        List<Long> latenciesMs = Collections.synchronizedList(new ArrayList<>(CONCURRENT_REQUESTS));

        ExecutorService pool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            final TokenUser tu = tokens.get(i);
            pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                long t0 = System.nanoTime();
                try {
                    HttpHeaders h = new HttpHeaders();
                    h.set("authorization", tu.token);
                    h.setContentType(MediaType.APPLICATION_JSON);
                    ResponseEntity<String> resp = restTemplate.exchange(
                            BASE_URL + "/voucher-order/seckill/" + voucherId,
                            HttpMethod.POST,
                            new HttpEntity<>(h),
                            String.class);
                    long elapsed = (System.nanoTime() - t0) / 1_000_000;
                    latenciesMs.add(elapsed);

                    String body = resp.getBody();
                    if (body == null) {
                        errorCount.incrementAndGet();
                        return;
                    }
                    JsonNode node = objectMapper.readTree(body);
                    if (node.path("success").asBoolean(false)) {
                        okCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                        String msg = node.path("errorMsg").asText("(empty)");
                        errorMsgBuckets.computeIfAbsent(msg, k -> new AtomicInteger())
                                .incrementAndGet();
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    log.warn("[storm] request error: {}", e.toString());
                } finally {
                    done.countDown();
                }
            });
        }

        long stormStart = System.currentTimeMillis();
        start.countDown();   // 同步起跑
        done.await();
        long stormElapsedMs = System.currentTimeMillis() - stormStart;
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        log.info("=========== Storm completed in {} ms ===========", stormElapsedMs);
        log.info("ok={}, fail={}, error={}", okCount.get(), failCount.get(), errorCount.get());
        log.info("Lua script reject reasons: {}", errorMsgBuckets);

        // ===== 3. 等 MQ 消费 =====
        log.info("Waiting up to {}s for RocketMQ consumer to drain pending orders...",
                MQ_CONSUMER_WAIT_SECONDS);
        for (int i = 0; i < MQ_CONSUMER_WAIT_SECONDS; i++) {
            int dbOrders = countDbOrders(voucherId);
            int pending = countRedisPendingKeys();
            if (dbOrders >= okCount.get() && pending == 0) {
                log.info("MQ drained at t+{}s: dbOrders={}, pending={}", i + 1, dbOrders, pending);
                break;
            }
            if (i % 5 == 0) {
                log.info("  t+{}s: dbOrders={}, pending={}", i, dbOrders, pending);
            }
            Thread.sleep(1000);
        }

        // ===== 4. 收集最终指标 =====
        int finalDbOrders = countDbOrders(voucherId);
        int finalDistinctUsers = countDistinctUserOrders(voucherId);
        int finalRedisStock = readRedisStock(voucherId);
        int finalDbStock = readDbStock(voucherId);
        int finalPending = countRedisPendingKeys();

        // 排序计算分位
        List<Long> sortedLatencies = new ArrayList<>(latenciesMs);
        Collections.sort(sortedLatencies);
        long p50 = percentile(sortedLatencies, 50);
        long p95 = percentile(sortedLatencies, 95);
        long p99 = percentile(sortedLatencies, 99);
        long max = sortedLatencies.get(sortedLatencies.size() - 1);
        double avg = sortedLatencies.stream().mapToLong(Long::longValue).average().orElse(0);

        log.info("============================================================");
        log.info("                 Plan E Seckill Storm — REPORT              ");
        log.info("============================================================");
        log.info("Concurrency:      {} requests, {} threads", CONCURRENT_REQUESTS, THREAD_POOL_SIZE);
        log.info("Storm wall time:  {} ms ({} req/s)",
                stormElapsedMs, (CONCURRENT_REQUESTS * 1000L / Math.max(1, stormElapsedMs)));
        log.info("Initial stock:    {}", initialStock);
        log.info("HTTP outcomes:    ok={}  fail={}  error={}",
                okCount.get(), failCount.get(), errorCount.get());
        log.info("Lua rejects:      {}", errorMsgBuckets);
        log.info("DB orders:        {}  (distinct users={})", finalDbOrders, finalDistinctUsers);
        log.info("Final Redis stock:{}", finalRedisStock);
        log.info("Final DB stock:   {}", finalDbStock);
        log.info("Pending keys:     {}", finalPending);
        log.info("Latency ms:       avg={}, P50={}, P95={}, P99={}, max={}",
                String.format("%.1f", avg), p50, p95, p99, max);
        log.info("============================================================");

        // ===== 5. 断言 pass conditions =====
        assertEquals(initialStock, okCount.get(), "0 超卖：success 数应等于初始库存");
        assertEquals(okCount.get(), finalDbOrders, "DB 订单数应等于成功响应数（MQ 已消费完）");
        assertEquals(finalDbOrders, finalDistinctUsers, "一人一单：user_id 应不重复");
        assertEquals(0, finalRedisStock, "Redis 库存应清零");
        assertEquals(0, finalDbStock, "DB 库存应清零");
        assertEquals(0, finalPending, "pending key 应全部消费");
        // P95 不强断言（受机器影响），仅 log
    }

    // ===== 工具方法 =====

    private List<TokenUser> readTokens() throws IOException {
        List<TokenUser> tokens = new ArrayList<>();
        for (String line : Files.readAllLines(TOKENS_FILE, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            String[] parts = line.split(",", 2);
            tokens.add(new TokenUser(parts[0], Long.parseLong(parts[1])));
        }
        return tokens;
    }

    private int readRedisStock(long voucherId) {
        String v = stringRedisTemplate.opsForValue().get(RedisConstants.SECKILL_STOCK_KEY + voucherId);
        return v == null ? -1 : Integer.parseInt(v);
    }

    private int countRedisPendingKeys() {
        Set<String> keys = stringRedisTemplate.keys(RedisConstants.SECKILL_ORDER_PENDING_KEY + "*");
        return keys == null ? 0 : keys.size();
    }

    private int countDbOrders(long voucherId) {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id = ?",
                Integer.class, voucherId);
        return c == null ? 0 : c;
    }

    private int countDistinctUserOrders(long voucherId) {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT user_id) FROM tb_voucher_order WHERE voucher_id = ?",
                Integer.class, voucherId);
        return c == null ? 0 : c;
    }

    private int readDbStock(long voucherId) {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT stock FROM tb_seckill_voucher WHERE voucher_id = ?",
                Integer.class, voucherId);
        return c == null ? -1 : c;
    }

    private static long percentile(List<Long> sorted, int p) {
        if (sorted.isEmpty()) return 0;
        int idx = (int) Math.ceil(sorted.size() * p / 100.0) - 1;
        idx = Math.max(0, Math.min(idx, sorted.size() - 1));
        return sorted.get(idx);
    }

    private static class TokenUser {
        final String token;
        final long userId;

        TokenUser(String token, long userId) {
            this.token = token;
            this.userId = userId;
        }
    }
}
