package com.cityaihub.ai.rag.eval;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 评估期间的 query embedding 缓存。
 *
 * <p>评估会跑 50 query × 12 配置 = 600 次理论 embed，但其实每条 query 只需要 embed 一次。
 * 用 ConcurrentHashMap 把 query→vector 的映射缓存住，把 600 次降到 50 次（92% 节省）。
 */
@Slf4j
@Component
public class EmbeddingCache {

    private final EmbeddingModel embeddingModel;
    private final Map<String, float[]> cache = new ConcurrentHashMap<>();

    public EmbeddingCache(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public float[] embed(String query) {
        return cache.computeIfAbsent(query, q -> {
            try {
                return embeddingModel.embed(q);
            } catch (Exception e) {
                log.error("Failed to embed query [{}]: {}", q, e.toString());
                throw new IllegalStateException("Embed failed: " + q, e);
            }
        });
    }

    public int size() {
        return cache.size();
    }

    public void clear() {
        cache.clear();
    }
}
