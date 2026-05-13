package com.hmdp.ai.rag.eval;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

/**
 * Debug: review baseline K=50 全 0 现象.
 * 直接调 blogReviewVectorStore.similaritySearch(topK=50) 看 docs 的 distance/metadata.
 */
@Slf4j
@SpringBootTest(properties = {
        "ai.agent.bootstrap.enabled=false",
        "rag.eval.llm-judge.enabled=false",
        "rag.eval.generation.enabled=false",
        "rag.bm25.enabled=true",  // 跟 EvalRunnerTest 一致
})
class DebugBlogReviewK50Test {

    @Autowired @Qualifier("blogReviewVectorStore")
    private VectorStore blogReviewVectorStore;

    @Test
    void inspectK50() {
        for (int K : new int[]{10, 50}) {
            log.info("\n========== K={} ==========", K);
            SearchRequest req = SearchRequest.builder()
                    .query("银泰百货在哪里？")
                    .topK(K)
                    .similarityThreshold(0.0)
                    .build();
            List<Document> hits = blogReviewVectorStore.similaritySearch(req);
            if (hits == null) hits = List.of();
            log.info("[K={}] returned {} docs", K, hits.size());
            for (int i = 0; i < Math.min(hits.size(), 5); i++) {
                Document d = hits.get(i);
                Map<String, Object> meta = d.getMetadata();
                Object dist = meta == null ? null : meta.get("distance");
                Object reviewId = meta == null ? null : meta.get("reviewId");
                String content = d.getText();
                String preview = content == null ? "" : content.substring(0, Math.min(60, content.length()));
                log.info("[K={}] rank={} reviewId={} distance={} score=1-d={} preview={}",
                        K, i + 1, reviewId, dist,
                        dist instanceof Number ? (1.0 - ((Number) dist).doubleValue()) : "N/A",
                        preview);
            }
            // 看 50 时 8/40/49 位置的 doc
            if (K == 50 && hits.size() >= 50) {
                for (int i : new int[]{8, 25, 49}) {
                    Document d = hits.get(i);
                    Object dist = d.getMetadata() == null ? null : d.getMetadata().get("distance");
                    log.info("[K=50 spot rank={}] distance={}", i + 1, dist);
                }
            }
        }
    }
}
