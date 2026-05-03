package com.hmdp.cache.broadcast;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * 单机部署下的默认广播实现：吞掉所有 publish。
 *
 * 集群部署时只要再注册一个 BroadcastChannel 实现（RocketMQ 或 Redis Pub/Sub），
 * @ConditionalOnMissingBean 会让本实现自动让位，CacheClient 完全无感。
 */
@Slf4j
@Component
@ConditionalOnMissingBean(BroadcastChannel.class)
public class NoOpBroadcastChannel implements BroadcastChannel {

    @Override
    public void publish(InvalidateMsg msg) {
        log.debug("NoOp broadcast skip publish, key={}", msg.getKey());
    }
}
