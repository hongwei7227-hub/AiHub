package com.hmdp.ai.rag.retriever;

import com.hmdp.ai.dto.BlogVectorHitDTO;
import com.hmdp.ai.dto.KnowledgeHitDTO;
import com.hmdp.ai.dto.ShopToolDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Plan E：包装 {@link AiRagRetriever}（向量召回）+ {@link Bm25Index}（BM25 召回），用 RRF 合并。
 *
 * <p>RRF (Reciprocal Rank Fusion) 公式：
 * <pre>
 *   score(doc) = Σ 1 / (k + rank_i(doc))    // k 默认 60，i 遍历每个 retriever
 * </pre>
 * 业界标准合并算法，对各 retriever 的 score 量纲差异 robust（向量 cosine ∈ [0,1]，BM25 不限上界）。
 *
 * <p><b>不破坏原有结构</b>：AiRagRetriever 完全不动，业务原本调用它的代码不受影响。
 * 此包装层独立按需启用（rag.bm25.enabled=true）。
 *
 * <p><b>返回类型保持与 AiRagRetriever 一致</b>（ShopToolDTO / BlogVectorHitDTO / KnowledgeHitDTO），
 * RagAnswerGenerator 切换 hybrid 模式时不用改 contexts 拼接逻辑。BM25 命中但 vector 未召回的 doc，
 * 只能填部分字段（businessId + content + name），其他字段 null（contexts 拼接时 nullSafe）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "rag.bm25.enabled", havingValue = "true")
public class HybridRagRetriever {

    private final AiRagRetriever vectorRetriever;
    private final Bm25Index bm25Index;

    @Value("${rag.hybrid.rrf-k:60}")
    private int rrfK;

    @Value("${rag.hybrid.fetch-k:20}")
    private int fetchK;

    public HybridRagRetriever(AiRagRetriever vectorRetriever, Bm25Index bm25Index) {
        this.vectorRetriever = vectorRetriever;
        this.bm25Index = bm25Index;
    }

    // ============================ Shop ============================

    public List<ShopToolDTO> hybridSearchShops(String query, int topK) {
        List<ShopToolDTO> vectorHits = safeVectorShops(query);
        List<Bm25Index.Bm25Hit> bm25Hits = safeBm25(Bm25Index.COLLECTION_SHOP, query);

        // 1. 算 RRF 分数（key = shopId.toString，统一 String 类型）
        Map<String, Double> rrf = new HashMap<>();
        for (int i = 0; i < vectorHits.size(); i++) {
            String id = String.valueOf(vectorHits.get(i).getShopId());
            rrf.merge(id, 1.0 / (rrfK + i + 1), Double::sum);
        }
        for (int i = 0; i < bm25Hits.size(); i++) {
            rrf.merge(bm25Hits.get(i).businessId, 1.0 / (rrfK + i + 1), Double::sum);
        }

        // 2. 排序取 topK
        List<String> topIds = topRrfIds(rrf, topK);

        // 3. 回查 ShopToolDTO（优先 vectorHits 里完整信息，bm25-only 命中构造简化版）
        Map<String, ShopToolDTO> vectorMap = new HashMap<>();
        for (ShopToolDTO s : vectorHits) {
            vectorMap.put(String.valueOf(s.getShopId()), s);
        }
        Map<String, Bm25Index.Bm25Hit> bm25Map = new HashMap<>();
        for (Bm25Index.Bm25Hit h : bm25Hits) {
            bm25Map.put(h.businessId, h);
        }
        List<ShopToolDTO> result = new ArrayList<>(topK);
        for (String id : topIds) {
            ShopToolDTO dto = vectorMap.get(id);
            if (dto != null) {
                result.add(dto);
            } else {
                Bm25Index.Bm25Hit b = bm25Map.get(id);
                if (b != null) {
                    result.add(buildShopFromBm25(b));
                }
            }
        }
        log.debug("[hybrid-shop] q='{}' vector={} bm25={} merged={}",
                truncate(query, 40), vectorHits.size(), bm25Hits.size(), result.size());
        return result;
    }

    private ShopToolDTO buildShopFromBm25(Bm25Index.Bm25Hit b) {
        ShopToolDTO dto = new ShopToolDTO();
        try {
            dto.setShopId(Long.parseLong(b.businessId));
        } catch (NumberFormatException ignore) {
            // 反推数据里 shop_id 可能是 "restId_45623" 这种字符串，转 Long 失败时填 0
            dto.setShopId(0L);
        }
        dto.setName(b.name == null || b.name.isEmpty() ? ("shop_" + b.businessId) : b.name);
        // 没有 city/category/avg_price 等字段——RagAnswerGenerator 的 formatShops 用 nullSafe 处理
        return dto;
    }

    // ============================ Review ============================

    public List<BlogVectorHitDTO> hybridSearchReviews(String query, int topK) {
        List<BlogVectorHitDTO> vectorHits = safeVectorReviews(query);
        List<Bm25Index.Bm25Hit> bm25Hits = safeBm25(Bm25Index.COLLECTION_REVIEW, query);

        Map<String, Double> rrf = new HashMap<>();
        for (int i = 0; i < vectorHits.size(); i++) {
            rrf.merge(String.valueOf(vectorHits.get(i).getBlogId()), 1.0 / (rrfK + i + 1), Double::sum);
        }
        for (int i = 0; i < bm25Hits.size(); i++) {
            rrf.merge(bm25Hits.get(i).businessId, 1.0 / (rrfK + i + 1), Double::sum);
        }

        List<String> topIds = topRrfIds(rrf, topK);

        Map<String, BlogVectorHitDTO> vectorMap = new HashMap<>();
        for (BlogVectorHitDTO b : vectorHits) {
            vectorMap.put(String.valueOf(b.getBlogId()), b);
        }
        Map<String, Bm25Index.Bm25Hit> bm25Map = new HashMap<>();
        for (Bm25Index.Bm25Hit h : bm25Hits) {
            bm25Map.put(h.businessId, h);
        }
        List<BlogVectorHitDTO> result = new ArrayList<>(topK);
        for (String id : topIds) {
            BlogVectorHitDTO dto = vectorMap.get(id);
            if (dto != null) {
                result.add(dto);
            } else {
                Bm25Index.Bm25Hit b = bm25Map.get(id);
                if (b != null) {
                    result.add(buildReviewFromBm25(b));
                }
            }
        }
        log.debug("[hybrid-review] q='{}' vector={} bm25={} merged={}",
                truncate(query, 40), vectorHits.size(), bm25Hits.size(), result.size());
        return result;
    }

    private BlogVectorHitDTO buildReviewFromBm25(Bm25Index.Bm25Hit b) {
        BlogVectorHitDTO dto = new BlogVectorHitDTO();
        try {
            dto.setBlogId(Long.parseLong(b.businessId));
        } catch (NumberFormatException ignore) {
            dto.setBlogId(0L);
        }
        dto.setContent(b.content);
        // shop_id / userId / title 缺失，formatReviews 用 nullSafe
        return dto;
    }

    // ============================ Knowledge ============================

    public List<KnowledgeHitDTO> hybridSearchKnowledge(String query, int topK) {
        List<KnowledgeHitDTO> vectorHits = safeVectorKnowledge(query);
        List<Bm25Index.Bm25Hit> bm25Hits = safeBm25(Bm25Index.COLLECTION_KNOWLEDGE, query);

        Map<String, Double> rrf = new HashMap<>();
        for (int i = 0; i < vectorHits.size(); i++) {
            rrf.merge(String.valueOf(vectorHits.get(i).getId()), 1.0 / (rrfK + i + 1), Double::sum);
        }
        for (int i = 0; i < bm25Hits.size(); i++) {
            rrf.merge(bm25Hits.get(i).businessId, 1.0 / (rrfK + i + 1), Double::sum);
        }

        List<String> topIds = topRrfIds(rrf, topK);

        Map<String, KnowledgeHitDTO> vectorMap = new HashMap<>();
        for (KnowledgeHitDTO k : vectorHits) {
            vectorMap.put(String.valueOf(k.getId()), k);
        }
        Map<String, Bm25Index.Bm25Hit> bm25Map = new HashMap<>();
        for (Bm25Index.Bm25Hit h : bm25Hits) {
            bm25Map.put(h.businessId, h);
        }
        List<KnowledgeHitDTO> result = new ArrayList<>(topK);
        for (String id : topIds) {
            KnowledgeHitDTO dto = vectorMap.get(id);
            if (dto != null) {
                result.add(dto);
            } else {
                Bm25Index.Bm25Hit b = bm25Map.get(id);
                if (b != null) {
                    result.add(buildKnowledgeFromBm25(b));
                }
            }
        }
        log.debug("[hybrid-knowledge] q='{}' vector={} bm25={} merged={}",
                truncate(query, 40), vectorHits.size(), bm25Hits.size(), result.size());
        return result;
    }

    private KnowledgeHitDTO buildKnowledgeFromBm25(Bm25Index.Bm25Hit b) {
        KnowledgeHitDTO dto = new KnowledgeHitDTO();
        dto.setId(b.businessId);
        dto.setContent(b.content);
        return dto;
    }

    // ============================ helpers ============================

    private List<ShopToolDTO> safeVectorShops(String query) {
        try {
            return vectorRetriever.searchShopProfiles(query, fetchK);
        } catch (Exception e) {
            log.warn("[hybrid] vector shop search failed: {}", e.toString());
            return List.of();
        }
    }

    private List<BlogVectorHitDTO> safeVectorReviews(String query) {
        try {
            return vectorRetriever.searchBlogReviews(query, fetchK);
        } catch (Exception e) {
            log.warn("[hybrid] vector review search failed: {}", e.toString());
            return List.of();
        }
    }

    private List<KnowledgeHitDTO> safeVectorKnowledge(String query) {
        try {
            // AiRagRetriever 的 searchKnowledge 没有 topK 参数，内部按业务 RAG topK 配置
            return vectorRetriever.searchKnowledge(query);
        } catch (Exception e) {
            log.warn("[hybrid] vector knowledge search failed: {}", e.toString());
            return List.of();
        }
    }

    private List<Bm25Index.Bm25Hit> safeBm25(String collection, String query) {
        try {
            return bm25Index.search(collection, query, fetchK);
        } catch (Exception e) {
            log.warn("[hybrid] bm25 search failed for {}: {}", collection, e.toString());
            return List.of();
        }
    }

    /**
     * 取 RRF map 里 score 最高的前 topK 个 id。
     */
    private List<String> topRrfIds(Map<String, Double> rrf, int topK) {
        return rrf.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(Map.Entry::getKey)
                .toList();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
