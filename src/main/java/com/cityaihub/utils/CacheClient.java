package com.cityaihub.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.cityaihub.utils.RedisConstants.CACHE_NULL_TTL;
import static com.cityaihub.utils.RedisConstants.LOCK_SHOP_KEY;

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;
    private final Cache<String, Object> caffeineCache;

    @Value("${caffeine.enabled:false}")
    private boolean enableCaffeineCache;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public CacheClient(StringRedisTemplate stringRedisTemplate, Cache<String, Object> caffeineCache) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.caffeineCache = caffeineCache;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        if (enableCaffeineCache) {
            caffeineCache.put(key, value);
        }
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void delete(String key) {
        if (enableCaffeineCache) {
            caffeineCache.invalidate(key);
        }
        stringRedisTemplate.delete(key);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // 设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        if (enableCaffeineCache) {
            caffeineCache.put(key, redisData);
        }
        // 写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R,ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;
        // 0.先查 Caffeine L1 缓存
        if (enableCaffeineCache) {
            Object cached = caffeineCache.getIfPresent(key);
            if (cached != null) {
                if (cached instanceof String s && s.isEmpty()) return null;
                return (R) cached;
            }
        }
        // 1.从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(json)) {
            // 3.存在，回写 Caffeine 后返回
            R r = JSONUtil.toBean(json, type);
            if (enableCaffeineCache) caffeineCache.put(key, r);
            return r;
        }
        // 判断命中的是否是空值
        if (json != null) {
            if (enableCaffeineCache) caffeineCache.put(key, "");
            return null;
        }

        // 4.不存在，根据id查询数据库
        R r = dbFallback.apply(id);
        // 5.不存在，返回错误
        if (r == null) {
            if (enableCaffeineCache) caffeineCache.put(key, "");
            // 将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 6.存在，写入两层缓存
        this.set(key, r, time, unit);
        return r;
    }

    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 0.先查 Caffeine L1 缓存
        if (enableCaffeineCache) {
            Object cached = caffeineCache.getIfPresent(key);
            if (cached instanceof RedisData rd) {
                R r = JSONUtil.toBean((JSONObject) rd.getData(), type);
                if (rd.getExpireTime().isAfter(LocalDateTime.now())) return r;
                // 已逻辑过期，继续走 Redis 重建逻辑
            }
        }
        // 1.从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isBlank(json)) {
            return null;
        }
        // 3.命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 4.判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())) {
            // 4.1.未过期，回写 Caffeine 后返回
            if (enableCaffeineCache) caffeineCache.put(key, redisData);
            return r;
        }
        // 4.2.已过期，需要缓存重建
        // 5.获取互斥锁（SimpleRedisLock：UUID 标识 + Lua 原子释放）
        String lockKey = LOCK_SHOP_KEY + id;
        SimpleRedisLock lock = new SimpleRedisLock(lockKey, stringRedisTemplate);
        boolean isLock = lock.tryLock(0, 10, TimeUnit.SECONDS);
        // 6.判断是否获取锁成功
        if (isLock) {
            // 6.1.成功，开启独立线程，实现缓存重建
            // 注意：SimpleRedisLock 用线程 ID 校验持有者，异步线程的 ID 与主线程不同，
            // 跨线程 unlock 会被 Lua 校验拒绝。这里不显式释放，让锁靠 leaseTime（10s）
            // 自然过期 —— 同时附带"10s 内防止重复触发重建"的限流效果。
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R newR = dbFallback.apply(id);
                    this.setWithLogicalExpire(key, newR, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
        // 6.2.返回过期的商铺信息
        return r;
    }

    public <R, ID> R queryWithMutex(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 0.先查 Caffeine L1 缓存
        if (enableCaffeineCache) {
            Object cached = caffeineCache.getIfPresent(key);
            if (cached != null) {
                if (cached instanceof String s && s.isEmpty()) return null;
                return (R) cached;
            }
        }
        // 1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 3.存在，回写 Caffeine 后返回
            R r = JSONUtil.toBean(shopJson, type);
            if (enableCaffeineCache) caffeineCache.put(key, r);
            return r;
        }
        // 判断命中的是否是空值
        if (shopJson != null) {
            if (enableCaffeineCache) caffeineCache.put(key, "");
            return null;
        }

        // 4.实现缓存重建：用 SimpleRedisLock 互斥（UUID 标识 + Lua 原子释放）
        String lockKey = LOCK_SHOP_KEY + id;
        SimpleRedisLock lock = new SimpleRedisLock(lockKey, stringRedisTemplate);
        boolean isLock = lock.tryLock(0, 10, TimeUnit.SECONDS);
        if (!isLock) {
            // 拿不到锁：sleep 后递归重试。注意：递归调用必须在 try-finally 之外，
            // 否则未持锁的本线程在 finally 里调 unlock 会误删别人的锁
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            return queryWithMutex(keyPrefix, id, type, dbFallback, time, unit);
        }
        // 已拿到锁，try-finally 保证释放
        R r;
        try {
            r = dbFallback.apply(id);
            if (r == null) {
                if (enableCaffeineCache) caffeineCache.put(key, "");
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 5.存在，写入两层缓存
            this.set(key, r, time, unit);
        } finally {
            lock.unlock();
        }
        return r;
    }
}
