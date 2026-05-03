package com.hmdp.cache.broadcast;

/**
 * 缓存失效广播通道。
 *
 * 单机部署时由 NoOp 实现承担，调用即吞掉，不引入任何外部依赖；
 * 集群部署时切换为 RocketMQ 或 Redis Pub/Sub 实现，把 invalidate 消息广播到所有节点。
 *
 * 抽象成接口的目的：
 *  - 让 CacheClient 不感知具体广播载体
 *  - 不同集群规模可以切换实现而不改业务代码
 *  - 单机模式下零成本（NoOp 直接 return）
 */
public interface BroadcastChannel {

    void publish(InvalidateMsg msg);
}
