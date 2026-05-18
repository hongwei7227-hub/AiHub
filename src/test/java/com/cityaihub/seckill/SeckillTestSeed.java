package com.hmdp.seckill;

import com.hmdp.entity.User;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Plan E 压测前置：造一张秒杀券 + 1000 个用户 token。
 *
 * <p>默认 @Disabled，手动触发：
 * <pre>
 *   mvn '-Djunit.jupiter.conditions.deactivate=org.junit.*DisabledCondition' \
 *       '-Dtest=SeckillTestSeed' test
 * </pre>
 *
 * <p>需要：MySQL + Redis docker UP，application.yaml 默认 profile（不要 local，不需要 QWeather/Tavily）。
 */
@Slf4j
@Disabled("Manual seed before SeckillLoadTest. Requires Redis+MySQL up.")
@SpringBootTest(properties = "ai.agent.bootstrap.enabled=false")
class SeckillTestSeed {

    private static final Path VOUCHER_ID_FILE = Path.of("target", "test-classes", "seckill-voucher-id.txt");
    private static final Path TOKENS_FILE = Path.of("target", "test-classes", "seckill-tokens.csv");
    private static final int STOCK = 200;
    private static final int TOKEN_COUNT = 1000;

    @Autowired
    private IVoucherService voucherService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 造一张秒杀券：tb_voucher type=1, tb_seckill_voucher stock=200, Redis seckill:stock:{id}=200
     * 把生成的 voucherId 写到 target/test-classes/seckill-voucher-id.txt 给压测用
     */
    @Test
    void createSeckillVoucher() throws IOException {
        Voucher voucher = new Voucher();
        voucher.setShopId(1L);
        voucher.setTitle("Plan E 压测秒杀券 100-50");
        voucher.setSubTitle("仅压测用");
        voucher.setRules("仅限测试");
        voucher.setPayValue(10000L);   // 100 元
        voucher.setActualValue(5000L); // 50 元抵扣
        voucher.setType(1);  // 1=秒杀券
        voucher.setStock(STOCK);
        voucher.setBeginTime(LocalDateTime.now().minusMinutes(1));  // 立即生效
        voucher.setEndTime(LocalDateTime.now().plusDays(1));

        voucherService.addSeckillVoucher(voucher);
        Long voucherId = voucher.getId();
        log.info("[seckill-seed] created seckill voucher id={} stock={}", voucherId, STOCK);

        // 验证 Redis 库存预热
        String redisStock = stringRedisTemplate.opsForValue().get(RedisConstants.SECKILL_STOCK_KEY + voucherId);
        log.info("[seckill-seed] Redis stock preheat: {} = {}",
                RedisConstants.SECKILL_STOCK_KEY + voucherId, redisStock);

        Files.createDirectories(VOUCHER_ID_FILE.getParent());
        Files.writeString(VOUCHER_ID_FILE, voucherId.toString(), StandardCharsets.UTF_8);
        log.info("[seckill-seed] voucher id written to {}", VOUCHER_ID_FILE.toAbsolutePath());
    }

    /**
     * 批量造 token：取前 TOKEN_COUNT 个 user，给每人发一个 UUID token，
     * 直接写 Redis hash login:token:{uuid} → {userId, phone, nickName} TTL 36000s。
     * 把 (token, userId) 列表写到 target/test-classes/seckill-tokens.csv 给压测读。
     */
    @Test
    void seedTokens() throws IOException {
        List<User> users = userMapper.selectList(null);
        if (users.size() < TOKEN_COUNT) {
            log.warn("[seckill-seed] only {} users in DB, expected >= {}; using all", users.size(), TOKEN_COUNT);
        }
        int n = Math.min(users.size(), TOKEN_COUNT);

        StringBuilder csv = new StringBuilder(n * 64);
        for (int i = 0; i < n; i++) {
            User u = users.get(i);
            String token = UUID.randomUUID().toString();
            Map<String, String> userHash = new HashMap<>();
            userHash.put("id", String.valueOf(u.getId()));
            userHash.put("nickName", u.getNickName() == null ? ("user_" + u.getId()) : u.getNickName());
            // RefreshTokenInterceptor 用 UserDTO 字段；nickName + id 足够通过鉴权填 UserHolder
            String key = RedisConstants.LOGIN_USER_KEY + token;
            stringRedisTemplate.opsForHash().putAll(key, userHash);
            stringRedisTemplate.expire(key, Duration.ofSeconds(RedisConstants.LOGIN_USER_TTL * 60));
            csv.append(token).append(',').append(u.getId()).append('\n');
        }

        Files.createDirectories(TOKENS_FILE.getParent());
        Files.writeString(TOKENS_FILE, csv.toString(), StandardCharsets.UTF_8);
        log.info("[seckill-seed] seeded {} tokens to Redis + wrote CSV: {}", n, TOKENS_FILE.toAbsolutePath());
    }
}
