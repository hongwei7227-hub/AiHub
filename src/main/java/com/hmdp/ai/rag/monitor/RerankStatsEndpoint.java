package com.hmdp.ai.rag.monitor;

import com.hmdp.ai.rag.retriever.Bge3Reranker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Plan G+：暴露 reranker 监控指标到 actuator。
 *
 * <p>访问方式：{@code GET /actuator/rerank-stats}
 *
 * <p>响应字段：
 * <ul>
 *   <li>{@code enabled} —— 是否启用了 reranker（{@code rag.rerank.enabled=true} 才有数据）</li>
 *   <li>{@code callCount} —— 累计调用次数（含重试，每次 attempt +1）</li>
 *   <li>{@code failureCount} —— 累计 fallback 到 identity order 的次数（所有重试都失败才计）</li>
 *   <li>{@code failureRate} —— failureCount / callCount，0~1</li>
 *   <li>{@code avgLatencyMs} —— 平均单次调用延迟（含重试间隔）</li>
 * </ul>
 *
 * <p><b>为什么用自定义 Endpoint 而不是 Micrometer MeterRegistry</b>：用户场景 = curl 看数字，
 * MeterRegistry 注册成 /actuator/metrics/{name} 一次只能看一个 metric，要看 4 个数得 4 次 curl；
 * 自定义 Endpoint 一次 JSON 返回全部，演示更清爽。
 */
@Component
@Endpoint(id = "rerank-stats")
public class RerankStatsEndpoint {

    private final Bge3Reranker reranker;

    public RerankStatsEndpoint(@Autowired(required = false) Bge3Reranker reranker) {
        this.reranker = reranker;
    }

    @ReadOperation
    public Map<String, Object> stats() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (reranker == null) {
            result.put("enabled", false);
            result.put("note", "rag.rerank.enabled=false 或 Bge3Reranker bean 未创建");
            return result;
        }
        long calls = reranker.callCount();
        long failures = reranker.failureCount();
        double failureRate = calls == 0 ? 0.0 : (double) failures / calls;
        result.put("enabled", true);
        result.put("callCount", calls);
        result.put("failureCount", failures);
        result.put("failureRate", failureRate);
        result.put("avgLatencyMs", reranker.avgLatencyMs());
        return result;
    }
}
