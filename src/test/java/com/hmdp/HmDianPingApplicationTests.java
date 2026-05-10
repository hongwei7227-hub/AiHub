package com.hmdp;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Shop;
import com.hmdp.entity.User;
import com.hmdp.service.impl.SeckillVoucherServiceImpl;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import jakarta.annotation.Resource;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;
import cn.hutool.core.lang.UUID;
@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private CacheClient cacheClient;

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private SeckillVoucherServiceImpl seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private ExecutorService es = Executors.newFixedThreadPool(500);
    @Test
    void createToken() throws IOException {
        PrintWriter printWriter = new PrintWriter(new FileWriter("D:\\tokens.txt"));
        for (int i = 0; i < 1000; i++) {
            // 1. 生成用户
            User user = new User();
            user.setPhone("1380000" + String.format("%04d", i));
            user.setNickName("user_" + i);
            user.setIcon("");

            // 🔥🔥🔥 关键修正：手动给一个 ID！否则 Redis 里没有 ID！🔥🔥🔥
            // 假设数据库里已经有了这些用户，或者我们只是模拟测试，给个假ID即可
            user.setId((long) (i + 1));

            // 2. 转为 UserDTO
            UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);

            // 3. 生成 Token
            String token = UUID.randomUUID().toString(true);

            // 4. 存入 Redis
            String tokenKey = "login:token:" + token;

            Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                    CopyOptions.create()
                            .setIgnoreNullValue(true)
                            .setFieldValueEditor((fieldName, fieldValue) -> {
                                if (fieldValue == null) {
                                    return null;
                                }
                                return fieldValue.toString();
                            }));

            stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
            // 设置有效期 30 分钟 (注意单位)
            stringRedisTemplate.expire(tokenKey, 30000000, TimeUnit.MINUTES);

            // 5. 写入文件
            printWriter.print(token + "\n");
        }
        printWriter.close();
        System.out.println("Token 生成完毕！");
    }
    @Test
    void testIdWorker() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);

        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id = " + id);
            }
            latch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("time = " + (end - begin));
    }

    @Test
    void testSaveShop() throws InterruptedException {
        Shop shop = shopService.getById(1L);
        cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY + 1L, shop, 10L, TimeUnit.SECONDS);
    }

    @Test
    void loadShopData() {
        // 1.查询店铺信息
        List<Shop> list = shopService.list();
        // 2.把店铺分组，按照typeId分组，typeId一致的放到一个集合
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        // 3.分批完成写入Redis
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            // 3.1.获取类型id
            Long typeId = entry.getKey();
            String key = SHOP_GEO_KEY + typeId;
            // 3.2.获取同类型的店铺的集合
            List<Shop> value = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());
            // 3.3.写入redis GEOADD key 经度 纬度 member
            for (Shop shop : value) {
                // stringRedisTemplate.opsForGeo().add(key, new Point(shop.getX(), shop.getY()), shop.getId().toString());
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(), shop.getY())
                ));
            }
            stringRedisTemplate.opsForGeo().add(key, locations);
        }
    }

    @Test
    void testHyperLogLog() {
        String[] values = new String[1000];
        int j = 0;
        for (int i = 0; i < 1000000; i++) {
            j = i % 1000;
            values[j] = "user_" + i;
            if(j == 999){
                // 发送到Redis
                stringRedisTemplate.opsForHyperLogLog().add("hl2", values);
            }
        }
        // 统计数量
        Long count = stringRedisTemplate.opsForHyperLogLog().size("hl2");
        System.out.println("count = " + count);
    }

    @Test
    void loadSeckillVoucher() {
        List<SeckillVoucher> list = seckillVoucherService.list();
        if (list == null || list.isEmpty()) {
            System.out.println("没有秒杀优惠券数据");
            return;
        }
        for (SeckillVoucher voucher : list) {
            String stockKey = "seckill:stock:" + voucher.getVoucherId();
            stringRedisTemplate.opsForValue().set(stockKey, String.valueOf(voucher.getStock()));
            System.out.println("预热优惠券库存成功，voucherId=" + voucher.getVoucherId() + "，stock=" + voucher.getStock());
        }
        System.out.println("所有秒杀优惠券库存预热完成，共 " + list.size() + " 条");
    }

    /**
     * Plan G+：把 hm-dianping-data-prep 跑出的 shop_profile_enriched.jsonl(已含高德补的 x/y/real_address)
     * 灌进 tb_shop，让业务侧 searchNearbyShops 工具能召回真实店铺。
     *
     * 跑完之后必须再跑一次 loadShopData 才会重建 Redis GEO 索引。
     */
    @Test
    void enrichShopsFromJsonl() throws Exception {
        // 杭州 bbox(119.5-120.7 经度 / 29.5-30.6 纬度)。citylimit=true 不是 100% 可靠,
        // 高德偶尔会漏进厦门 / 南宁 / 北京 / 宁波等坐标。此处兜底过滤。
        final double XMIN = 119.5, XMAX = 120.7, YMIN = 29.5, YMAX = 30.6;

        // 0. 清理之前可能已灌进去的 out-of-bounds 数据(保留 id<=14 教学初始数据)
        boolean cleaned = shopService.lambdaUpdate()
                .gt(Shop::getId, 14L)
                .and(w -> w.lt(Shop::getX, XMIN).or().gt(Shop::getX, XMAX).or().lt(Shop::getY, YMIN).or().gt(Shop::getY, YMAX))
                .remove();
        System.out.println("  cleanup out-of-bounds rows: " + cleaned);

        java.nio.file.Path file = java.nio.file.Paths.get("F:/project/hm-dianping-data-prep/output/shop_profile_enriched.jsonl");
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        java.util.regex.Pattern numPattern = java.util.regex.Pattern.compile("\\d+");

        List<Shop> shops = new ArrayList<>();
        int skipNoCoords = 0, skipExisting = 0, skipOutOfBounds = 0, restIdRenamed = 0;

        for (String line : java.nio.file.Files.readAllLines(file)) {
            if (line.isBlank()) continue;
            com.fasterxml.jackson.databind.JsonNode n = mapper.readTree(line);

            long id = n.get("shop_id").asLong();
            // 不覆盖现有 14 条教学初始数据(id 1-14)
            if (id <= 14) { skipExisting++; continue; }

            com.fasterxml.jackson.databind.JsonNode xN = n.get("x");
            com.fasterxml.jackson.databind.JsonNode yN = n.get("y");
            // 没坐标的(268 条 not_found)直接跳过 -- Redis GEO 用不了
            if (xN == null || xN.isNull() || yN == null || yN.isNull()) {
                skipNoCoords++;
                continue;
            }
            double xVal = xN.asDouble();
            double yVal = yN.asDouble();
            // bbox 过滤(高德 citylimit 不是 100% 可靠,有时漏厦门/北京/南宁等坐标)
            if (xVal < XMIN || xVal > XMAX || yVal < YMIN || yVal > YMAX) {
                skipOutOfBounds++;
                continue;
            }

            // restId_xxx 改用 real_address 截断作 name(670 条有 real_address)
            String name = n.get("name").asText();
            if (name.startsWith("restId_")) {
                String real = n.has("real_address") ? n.get("real_address").asText() : "";
                if (real != null && !real.isBlank()) {
                    name = real.length() > 30 ? real.substring(0, 30) : real;
                    restIdRenamed++;
                }
            }

            // 地址优先用高德 real_address(完整带门牌),没有 fallback 原 address(街区级)
            String address = "";
            if (n.has("real_address") && !n.get("real_address").asText().isBlank()) {
                address = n.get("real_address").asText();
            } else if (n.has("address")) {
                address = n.get("address").asText();
            }

            // avgPrice 解析:从 "68-138" / "人均60" / "240多" 抽数字取平均
            Long avgPrice = null;
            if (n.has("avg_price_hint")) {
                String hint = n.get("avg_price_hint").asText();
                java.util.regex.Matcher m = numPattern.matcher(hint);
                int sum = 0, count = 0;
                while (m.find()) {
                    sum += Integer.parseInt(m.group());
                    count++;
                }
                if (count > 0) avgPrice = (long) (sum / count);
            }

            // score = rating * 10(rating 是 0-5 浮点,score 是 0-50 整数)
            int score = 0;
            if (n.has("rating") && !n.get("rating").isNull()) {
                score = (int) Math.round(n.get("rating").asDouble() * 10);
            }

            Shop shop = new Shop()
                    .setId(id)
                    .setName(name)
                    .setTypeId(1L)             // 全部美食(category 全是饮食类)
                    .setImages("")              // 高德不返图片,留空
                    .setAddress(address)
                    .setX(xVal)
                    .setY(yVal)
                    .setAvgPrice(avgPrice)
                    .setSold(0)
                    .setComments(n.has("review_count") ? n.get("review_count").asInt() : 0)
                    .setScore(score)
                    .setOpenHours("10:00-22:00")
                    .setCreateTime(java.time.LocalDateTime.now())
                    .setUpdateTime(java.time.LocalDateTime.now());
            shops.add(shop);
        }

        // saveOrUpdateBatch:重跑也安全(主键存在则更新,不存在则插入)
        boolean ok = shopService.saveOrUpdateBatch(shops, 100);

        System.out.println("=== enrichShopsFromJsonl 完成 ===");
        System.out.println("  灌入 tb_shop: " + shops.size() + " 条");
        System.out.println("  跳过 no-coords: " + skipNoCoords + " 条");
        System.out.println("  跳过 out-of-bounds: " + skipOutOfBounds + " 条");
        System.out.println("  跳过 id<=14: " + skipExisting + " 条");
        System.out.println("  restId 用 real_address 改名: " + restIdRenamed + " 条");
        System.out.println("  saveOrUpdateBatch ok=" + ok);
    }
}
