package com.cityaihub.ai.rag.retriever;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Plan G：Cross-encoder Reranker (BAAI/bge-reranker-v2-m3 via SiliconFlow)。
 *
 * <p>用途：召回完 top-K 后，用 cross-encoder 对 (query, doc) 对精排，提升相关度区分度。
 *
 * <p><b>不依赖 Spring AI ChatModel</b>：rerank 不是 chat completion，是独立的 cross-encoder 评分接口
 * (OpenAI / Cohere-style schema)。直接用 Spring 内置的 {@link RestClient} 调 SiliconFlow
 * {@code /v1/rerank} 端点：
 *
 * <pre>
 *   POST /v1/rerank
 *   {
 *     "model": "BAAI/bge-reranker-v2-m3",
 *     "query": "...",
 *     "documents": ["...", "...", ...],
 *     "top_n": 5,
 *     "return_documents": false
 *   }
 *   →
 *   {
 *     "results": [
 *       {"index": 2, "relevance_score": 0.95},
 *       {"index": 0, "relevance_score": 0.83},
 *       ...
 *     ]
 *   }
 * </pre>
 *
 * <p><b>失败兜底</b>：API 调用失败 / 超时 / 返回异常时，返回 {@code [0, 1, 2, ..., topK-1]}
 * （保持原序），不抛异常——让 rerank 故障降级回 RRF 原顺序，整条 RAG pipeline 不挂。
 *
 * <p><b>启用方式</b>：默认 {@code rag.rerank.enabled=false} 不创建 bean。EvalRunnerTest 用
 * {@code @SpringBootTest(properties = "rag.rerank.enabled=true")} 显式打开。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "rag.rerank.enabled", havingValue = "true")
public class Bge3Reranker {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${rag.rerank.base-url}")
    private String baseUrl;

    @Value("${rag.rerank.api-key}")
    private String apiKey;

    @Value("${rag.rerank.model}")
    private String model;

    @Value("${rag.rerank.timeout-ms:5000}")
    private int timeoutMs;

    @Value("${rag.rerank.max-retries:2}")
    private int maxRetries;

    private RestClient client;
    private final AtomicLong callCount = new AtomicLong();
    private final AtomicLong failureCount = new AtomicLong();
    private final AtomicLong totalLatencyMs = new AtomicLong();

    @PostConstruct
    void init() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "rag.rerank.api-key is blank. Set spring.ai.openai.api-key (业务复用) or RAG_RERANK_API_KEY env.");
        }
        // R7: yaml 占位符链 ${RAG_RERANK_API_KEY:${spring.ai.openai.api-key}} 可能 resolve 失败成字面量
        // 字符串，过 blank 校验但调 SiliconFlow 401。打印前 4 位 + 长度脱敏 log 验证。
        if (apiKey.startsWith("${")) {
            log.error("[rerank] api-key looks like an unresolved placeholder: '{}'. " +
                    "Check yaml placeholder chain or set RAG_RERANK_API_KEY explicitly.", apiKey);
        }
        String maskedKey = apiKey.length() <= 8
                ? "***"
                : apiKey.substring(0, 4) + "***(len=" + apiKey.length() + ")";
        log.info("[rerank] init Bge3Reranker base-url={} model={} timeout={}ms apiKey={}",
                baseUrl, model, timeoutMs, maskedKey);
        // Spring 内置 RestClient，不引新依赖。Spring Boot 3.2 默认用 JDK HttpClient，连接超时与读超时
        // 走 default settings；timeout-ms 通过 RestClient.requestFactory 控制
        var requestFactory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeoutMs);
        requestFactory.setReadTimeout(timeoutMs);
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * 给定 query 和候选 documents，返回按 rerank score 降序的文档索引（取前 topK）。
     *
     * @param query     用户查询
     * @param documents 候选文档文本列表（必须与原 retriever 召回顺序一致，索引对应原列表 index）
     * @param topK      返回前 topK 个索引
     * @return 索引列表，长度 ≤ topK；失败时返回 [0, 1, ..., min(topK, documents.size())-1]
     */
    public List<Integer> rerank(String query, List<String> documents, int topK) {
        if (query == null || query.isBlank() || documents == null || documents.isEmpty()) {
            return identityOrder(documents == null ? 0 : documents.size(), topK);
        }
        // 边界：候选数 ≤ topK 时直接返回 identity（rerank 排序无意义）
        if (documents.size() <= 1) {
            return identityOrder(documents.size(), topK);
        }

        long start = System.currentTimeMillis();
        for (int attempt = 1; attempt <= maxRetries + 1; attempt++) {
            callCount.incrementAndGet();
            try {
                Map<String, Object> body = new HashMap<>();
                body.put("model", model);
                body.put("query", query);
                body.put("documents", documents);
                body.put("top_n", Math.min(topK, documents.size()));
                body.put("return_documents", false);

                String resp = client.post()
                        .uri("/v1/rerank")
                        .body(body)
                        .retrieve()
                        .body(String.class);

                List<Integer> indices = parseIndices(resp, documents.size(), topK);
                if (indices != null) {
                    long latency = System.currentTimeMillis() - start;
                    totalLatencyMs.addAndGet(latency);
                    log.debug("[rerank] q='{}' n={} top={} latency={}ms order={}",
                            truncate(query, 30), documents.size(), topK, latency, indices);
                    return indices;
                }
                log.warn("[rerank] attempt {}/{} parse failed, raw='{}'",
                        attempt, maxRetries + 1, truncate(resp, 200));
            } catch (Exception e) {
                log.warn("[rerank] attempt {}/{} call failed: {}",
                        attempt, maxRetries + 1, e.toString());
            }
            // 指数回退（attempt 1 → 200ms, attempt 2 → 400ms）
            if (attempt <= maxRetries) {
                try {
                    Thread.sleep(200L * (1L << (attempt - 1)));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        // 所有重试失败 → 降级回原序
        failureCount.incrementAndGet();
        log.warn("[rerank] all {} attempts failed for q='{}', fallback to identity order",
                maxRetries + 1, truncate(query, 60));
        return identityOrder(documents.size(), topK);
    }

    /**
     * 解析 SiliconFlow rerank 响应体为索引列表。
     * 容错：缺字段、score 不是数字、index 越界都按 null 返回触发重试或兜底。
     */
    static List<Integer> parseIndices(String json, int docCount, int topK) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode results = root.get("results");
            if (results == null || !results.isArray() || results.isEmpty()) return null;
            List<Integer> out = new ArrayList<>(Math.min(topK, results.size()));
            for (JsonNode item : results) {
                JsonNode idxNode = item.get("index");
                if (idxNode == null || !idxNode.isInt()) continue;
                int idx = idxNode.asInt();
                if (idx < 0 || idx >= docCount) continue;
                out.add(idx);
                if (out.size() >= topK) break;
            }
            return out.isEmpty() ? null : out;
        } catch (Exception e) {
            return null;
        }
    }

    private static List<Integer> identityOrder(int n, int topK) {
        int limit = Math.min(n, topK);
        List<Integer> out = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) out.add(i);
        return out;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // ===== 监控接口（EvalRunnerTest / Reranker 失败率检查用） =====

    public long callCount() {
        return callCount.get();
    }

    public long failureCount() {
        return failureCount.get();
    }

    public double avgLatencyMs() {
        long calls = callCount.get();
        return calls == 0 ? 0.0 : (double) totalLatencyMs.get() / calls;
    }
}
