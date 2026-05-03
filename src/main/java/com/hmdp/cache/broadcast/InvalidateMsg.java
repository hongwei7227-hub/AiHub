package com.hmdp.cache.broadcast;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 缓存失效广播消息。
 *
 * 初版就把 timestamp 放进协议里——一旦上线后再加字段需要灰度兼容，成本陡增。
 * 消费端可以根据 timestamp 判断消息是否陈旧（例如超过 5 分钟即丢弃），
 * 避免积压消息把已经被 TTL 兜底刷新过的缓存又强行清掉。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InvalidateMsg {

    private String key;

    /** 发送时刻（System.currentTimeMillis()），用于消费端判断消息新鲜度 */
    private long timestamp;

    public static InvalidateMsg of(String key) {
        return new InvalidateMsg(key, System.currentTimeMillis());
    }
}
