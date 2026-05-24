package com.cityaihub.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.cityaihub.dto.Result;
import com.cityaihub.entity.Shop;
import com.cityaihub.service.IShopService;
import com.cityaihub.utils.CacheClient;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.math3.distribution.ZipfDistribution;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 店铺二级缓存对照压测：Caffeine OFF (纯 Redis L2) vs Caffeine ON (L1+L2 二级)。
 *
 * <p>单次启动跑完两组：通过反射 toggle CacheClient.enableCaffeineCache 字段,
 * 每组前清空两层缓存 + 预热避免冷启动 miss 污染数据。
 *
 * <p>跑法:
 * <pre>
 *   mvn test -Dtest=ShopCacheLoadTest -DfailIfNoTests=false
 * </pre>
 * (Spring Boot 8083 端口业务实例不必关闭 — 测试用独立 SpringBootTest context,
 *  Redis/MySQL 共用,无端口冲突。)
 *
 * <p>输出:对比表 + Caffeine hitRate / Redis 命中估算。
 */
@Slf4j
@SpringBootTest(properties = "ai.agent.bootstrap.enabled=false")
class ShopCacheLoadTest {

    private static final int THREADS = 200;
    private static final int REQUESTS_PER_THREAD = 200;
    private static final List<Long> HOT_SHOP_IDS = List.of(100001L, 100050L, 100100L, 100200L, 100500L);
    private static final String CACHE_PREFIX = "cache:shop:";

    // PLAN-001: Zipfian 长尾压测参数
    private static final double ZIPF_SKEW = 0.8;
    private static final int COLD_START_REQS = 10000;
    private static final int WARMUP_REQS = 10000;
    private static final int STEADY_REQS = 40000;
    private List<Long> allShopIds;
    private ZipfDistribution zipf;

    @Autowired
    private IShopService shopService;

    @Autowired
    private CacheClient cacheClient;

    @Autowired
    private Cache<String, Object> caffeineCache;

    @Autowired
    private StringRedisTemplate redis;

    @Test
    void compareCaffeineEnabledVsDisabled() throws Exception {
        log.info("==========================================================");
        log.info("       店铺二级缓存压测: Caffeine OFF vs ON");
        log.info("==========================================================");
        log.info("配置: {} 并发线程 × {} 请求/线程 = {} 总请求",
                THREADS, REQUESTS_PER_THREAD, THREADS * REQUESTS_PER_THREAD);
        log.info("热点店铺(100001+ 玉泉真实店): {}", HOT_SHOP_IDS);
        log.info("");

        // ===== Group A: Caffeine OFF (纯 Redis L2) =====
        setCaffeineEnabled(false);
        clearAllCaches();
        warmup();
        Stats statsA = runLoad("Group A — Caffeine OFF (纯 Redis L2)");
        CacheStats caffeineAfterA = caffeineCache.stats();

        // ===== Group B: Caffeine ON (L1 + L2) =====
        setCaffeineEnabled(true);
        clearAllCaches();
        warmup();
        Stats statsB = runLoad("Group B — Caffeine ON (L1 Caffeine + L2 Redis)");
        CacheStats caffeineAfterB = caffeineCache.stats();

        // ===== 对比报告 =====
        printComparison(statsA, statsB, caffeineAfterA, caffeineAfterB);
    }

    private void setCaffeineEnabled(boolean enabled) throws Exception {
        Field f = CacheClient.class.getDeclaredField("enableCaffeineCache");
        f.setAccessible(true);
        f.setBoolean(cacheClient, enabled);
        log.info(">>> CacheClient.enableCaffeineCache 切换为 {}", enabled);
    }

    private void clearAllCaches() {
        for (Long id : HOT_SHOP_IDS) {
            String key = CACHE_PREFIX + id;
            redis.delete(key);
            caffeineCache.invalidate(key);
        }
        log.info(">>> 清空 Caffeine + Redis 中 {} 个热点 key", HOT_SHOP_IDS.size());
    }

    private void warmup() {
        // 顺序访问一次让 Redis 装载,避免压测第一拨请求全 miss 打 DB
        for (Long id : HOT_SHOP_IDS) {
            shopService.queryById(id);
        }
        log.info(">>> 预热完成({}个 key 已落 Redis)", HOT_SHOP_IDS.size());
    }

    private Stats runLoad(String label) throws Exception {
        log.info("--- 开始: {} ---", label);

        int total = THREADS * REQUESTS_PER_THREAD;
        long[] latenciesNs = new long[total];
        AtomicLong successCount = new AtomicLong();
        AtomicLong idx = new AtomicLong();
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);

        for (int t = 0; t < THREADS; t++) {
            final int threadIdx = t;
            pool.submit(() -> {
                try {
                    ready.countDown();
                    go.await();
                    for (int r = 0; r < REQUESTS_PER_THREAD; r++) {
                        Long id = HOT_SHOP_IDS.get((threadIdx + r) % HOT_SHOP_IDS.size());
                        long t0 = System.nanoTime();
                        Result result = shopService.queryById(id);
                        long dur = System.nanoTime() - t0;
                        latenciesNs[(int) idx.getAndIncrement()] = dur;
                        if (result != null && Boolean.TRUE.equals(result.getSuccess())) {
                            successCount.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    log.error("worker error", e);
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();          // 等所有线程就绪
        long start = System.nanoTime();
        go.countDown();         // 同时发车
        done.await();
        long elapsedNs = System.nanoTime() - start;
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        // 计算延迟分位
        List<Long> sorted = new ArrayList<>(total);
        for (long ns : latenciesNs) sorted.add(ns);
        Collections.sort(sorted);

        long avgNs = 0;
        for (long ns : latenciesNs) avgNs += ns;
        avgNs /= total;

        Stats s = new Stats();
        s.totalRequests = total;
        s.success = successCount.get();
        s.elapsedMs = elapsedNs / 1_000_000.0;
        s.qps = total * 1_000_000_000.0 / elapsedNs;
        s.avgMs = avgNs / 1_000_000.0;
        s.p50Ms = sorted.get((int) (total * 0.50)) / 1_000_000.0;
        s.p95Ms = sorted.get((int) (total * 0.95)) / 1_000_000.0;
        s.p99Ms = sorted.get((int) (total * 0.99)) / 1_000_000.0;
        s.maxMs = sorted.get(total - 1) / 1_000_000.0;

        log.info("  请求数:    {}", s.totalRequests);
        log.info("  成功数:    {}", s.success);
        log.info("  总耗时:    {} ms", String.format("%.1f", s.elapsedMs));
        log.info("  QPS:       {} req/s", String.format("%.1f", s.qps));
        log.info("  avg 延迟:  {} ms", String.format("%.3f", s.avgMs));
        log.info("  P50:       {} ms", String.format("%.3f", s.p50Ms));
        log.info("  P95:       {} ms", String.format("%.3f", s.p95Ms));
        log.info("  P99:       {} ms", String.format("%.3f", s.p99Ms));
        log.info("  max:       {} ms", String.format("%.3f", s.maxMs));
        return s;
    }

    private void printComparison(Stats a, Stats b, CacheStats caffeineA, CacheStats caffeineB) {
        double qpsGrowth = (b.qps - a.qps) / a.qps * 100;
        double avgDrop = (a.avgMs - b.avgMs) / a.avgMs * 100;
        double p95Drop = (a.p95Ms - b.p95Ms) / a.p95Ms * 100;

        log.info("");
        log.info("==========================================================");
        log.info("                  店铺二级缓存压测对比报告");
        log.info("==========================================================");
        log.info("指标             | Caffeine OFF (Redis only) | Caffeine ON (L1+L2)");
        log.info("-----------------+---------------------------+---------------------");
        log.info("QPS              | {} req/s   | {} req/s",
                pad(String.format("%.1f", a.qps), 14),
                pad(String.format("%.1f", b.qps), 14));
        log.info("avg latency      | {} ms        | {} ms",
                pad(String.format("%.3f", a.avgMs), 14),
                pad(String.format("%.3f", b.avgMs), 14));
        log.info("P50              | {} ms        | {} ms",
                pad(String.format("%.3f", a.p50Ms), 14),
                pad(String.format("%.3f", b.p50Ms), 14));
        log.info("P95              | {} ms        | {} ms",
                pad(String.format("%.3f", a.p95Ms), 14),
                pad(String.format("%.3f", b.p95Ms), 14));
        log.info("P99              | {} ms        | {} ms",
                pad(String.format("%.3f", a.p99Ms), 14),
                pad(String.format("%.3f", b.p99Ms), 14));
        log.info("max              | {} ms        | {} ms",
                pad(String.format("%.3f", a.maxMs), 14),
                pad(String.format("%.3f", b.maxMs), 14));
        log.info("==========================================================");
        log.info(">>> 简历可引用数字 <<<");
        log.info("    QPS:          {} → {} ({}{}%)",
                String.format("%.0f", a.qps), String.format("%.0f", b.qps),
                qpsGrowth >= 0 ? "+" : "", String.format("%.1f", qpsGrowth));
        log.info("    avg latency:  {} ms → {} ms ({}{}%)",
                String.format("%.2f", a.avgMs), String.format("%.2f", b.avgMs),
                avgDrop >= 0 ? "-" : "+", String.format("%.1f", Math.abs(avgDrop)));
        log.info("    P95 latency:  {} ms → {} ms ({}{}%)",
                String.format("%.2f", a.p95Ms), String.format("%.2f", b.p95Ms),
                p95Drop >= 0 ? "-" : "+", String.format("%.1f", Math.abs(p95Drop)));
        log.info("==========================================================");
        log.info("Caffeine 累计 stats (Group B 阶段):");
        log.info("    hitCount:  {}", caffeineB.hitCount() - caffeineA.hitCount());
        log.info("    missCount: {}", caffeineB.missCount() - caffeineA.missCount());
        log.info("    hitRate:   {}",
                String.format("%.2f%%", deltaHitRate(caffeineA, caffeineB) * 100));
        log.info("==========================================================");
    }

    private double deltaHitRate(CacheStats before, CacheStats after) {
        long deltaHit = after.hitCount() - before.hitCount();
        long deltaMiss = after.missCount() - before.missCount();
        long total = deltaHit + deltaMiss;
        return total == 0 ? 0 : (double) deltaHit / total;
    }

    private String pad(String s, int width) {
        if (s.length() >= width) return s;
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < width) sb.append(' ');
        return sb.toString();
    }

    private static class Stats {
        long totalRequests;
        long success;
        double elapsedMs;
        double qps;
        double avgMs;
        double p50Ms;
        double p95Ms;
        double p99Ms;
        double maxMs;
    }

    // ==================== PLAN-001: Zipfian 长尾压测 ====================

    @Test
    void zipfianLongTailLoadTest() throws Exception {
        log.info("==========================================================");
        log.info("       Zipfian 长尾压测: L1/L2/DB 分层命中率");
        log.info("==========================================================");

        loadAllShopIdsAndInitZipf();

        setCaffeineEnabled(true);
        clearAllCachesGlobal();
        cacheClient.resetHitCounters();

        // Stage 1: Cold Start
        Stats coldStats = runZipfLoad("Cold Start", COLD_START_REQS);
        long coldL1 = cacheClient.getL1HitCount();
        long coldL2 = cacheClient.getL2HitCount();
        long coldDb = cacheClient.getDbHitCount();
        long coldTotal = coldL1 + coldL2 + coldDb;

        // Stage 2: Warmup (不收集统计)
        cacheClient.resetHitCounters();
        runZipfLoad("Warmup", WARMUP_REQS);

        // Stage 3: Steady State
        cacheClient.resetHitCounters();
        Stats steadyStats = runZipfLoad("Steady State", STEADY_REQS);
        long steadyL1 = cacheClient.getL1HitCount();
        long steadyL2 = cacheClient.getL2HitCount();
        long steadyDb = cacheClient.getDbHitCount();
        long steadyTotal = steadyL1 + steadyL2 + steadyDb;

        // 输出报告
        log.info("");
        log.info("==========================================================");
        log.info("                  Zipfian 长尾压测报告");
        log.info("==========================================================");
        log.info("店铺总数:        {}", allShopIds.size());
        log.info("Zipfian 偏度:    {}", ZIPF_SKEW);
        log.info("");
        log.info("--- Cold Start ({}请求) ---", COLD_START_REQS);
        log.info("  QPS:    {} req/s", String.format("%.1f", coldStats.qps));
        log.info("  P95:    {} ms", String.format("%.3f", coldStats.p95Ms));
        log.info("  L1命中: {} ({}%)", coldL1, pct(coldL1, coldTotal));
        log.info("  L2命中: {} ({}%)", coldL2, pct(coldL2, coldTotal));
        log.info("  DB命中: {} ({}%)", coldDb, pct(coldDb, coldTotal));
        log.info("");
        log.info("--- Steady State ({}请求, 主指标) ---", STEADY_REQS);
        log.info("  QPS:    {} req/s", String.format("%.1f", steadyStats.qps));
        log.info("  P50:    {} ms", String.format("%.3f", steadyStats.p50Ms));
        log.info("  P95:    {} ms", String.format("%.3f", steadyStats.p95Ms));
        log.info("  P99:    {} ms", String.format("%.3f", steadyStats.p99Ms));
        log.info("  L1命中: {} ({}%)  ← Caffeine 内存", steadyL1, pct(steadyL1, steadyTotal));
        log.info("  L2命中: {} ({}%)  ← Redis 网络", steadyL2, pct(steadyL2, steadyTotal));
        log.info("  DB命中: {} ({}%)  ← MySQL 查询", steadyDb, pct(steadyDb, steadyTotal));
        log.info("==========================================================");
    }

    private void loadAllShopIdsAndInitZipf() {
        this.allShopIds = shopService.list().stream()
                .map(Shop::getId)
                .toList();
        int n = allShopIds.size();
        if (n < 100) {
            throw new IllegalStateException("店铺数 " + n + " < 100, Zipfian 测试无意义, 先灌库");
        }
        this.zipf = new ZipfDistribution(n, ZIPF_SKEW);
        log.info(">>> 加载 {} 个店铺 ID, Zipfian skew={}", n, ZIPF_SKEW);
    }

    private Long pickShopIdZipf(int seed) {
        int rank = zipf.sample();
        return allShopIds.get((rank - 1 + seed) % allShopIds.size());
    }

    private void clearAllCachesGlobal() {
        for (Long id : allShopIds) {
            String key = CACHE_PREFIX + id;
            redis.delete(key);
            caffeineCache.invalidate(key);
        }
        log.info(">>> 清空 {} 个店铺的两层缓存", allShopIds.size());
    }

    private Stats runZipfLoad(String label, int totalRequests) throws Exception {
        log.info("--- 开始: {} ({} 请求) ---", label, totalRequests);

        int threads = THREADS;
        int requestsPerThread = totalRequests / threads;
        int actualTotal = threads * requestsPerThread;

        long[] latenciesNs = new long[actualTotal];
        AtomicLong successCount = new AtomicLong();
        AtomicLong idx = new AtomicLong();
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int t = 0; t < threads; t++) {
            final int threadIdx = t;
            pool.submit(() -> {
                try {
                    ready.countDown();
                    go.await();
                    for (int r = 0; r < requestsPerThread; r++) {
                        Long id = pickShopIdZipf(threadIdx * 31 + r);
                        long t0 = System.nanoTime();
                        Result result = shopService.queryById(id);
                        long dur = System.nanoTime() - t0;
                        latenciesNs[(int) idx.getAndIncrement()] = dur;
                        if (result != null && Boolean.TRUE.equals(result.getSuccess())) {
                            successCount.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    log.error("worker error", e);
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        long start = System.nanoTime();
        go.countDown();
        done.await();
        long elapsedNs = System.nanoTime() - start;
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        List<Long> sorted = new ArrayList<>(actualTotal);
        for (long ns : latenciesNs) sorted.add(ns);
        Collections.sort(sorted);

        long avgNs = 0;
        for (long ns : latenciesNs) avgNs += ns;
        avgNs /= actualTotal;

        Stats s = new Stats();
        s.totalRequests = actualTotal;
        s.success = successCount.get();
        s.elapsedMs = elapsedNs / 1_000_000.0;
        s.qps = actualTotal * 1_000_000_000.0 / elapsedNs;
        s.avgMs = avgNs / 1_000_000.0;
        s.p50Ms = sorted.get((int) (actualTotal * 0.50)) / 1_000_000.0;
        s.p95Ms = sorted.get((int) (actualTotal * 0.95)) / 1_000_000.0;
        s.p99Ms = sorted.get((int) (actualTotal * 0.99)) / 1_000_000.0;
        s.maxMs = sorted.get(actualTotal - 1) / 1_000_000.0;
        return s;
    }

    private static String pct(long part, long total) {
        return total == 0 ? "0.0" : String.format("%.1f", part * 100.0 / total);
    }
}
