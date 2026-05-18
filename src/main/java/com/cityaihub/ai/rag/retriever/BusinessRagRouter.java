package com.hmdp.ai.rag.retriever;

import com.hmdp.ai.dto.BlogVectorHitDTO;
import com.hmdp.ai.dto.KnowledgeHitDTO;
import com.hmdp.ai.dto.ShopToolDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Plan G+：业务侧 RAG 检索路由层。
 *
 * <p>把"模式 dispatch + 故障降级"从 {@link com.hmdp.ai.tool.DianPingAgentTools} 的 @Tool 方法
 * 里抽出来，让工具方法只负责 @Tool 注解定义，路由逻辑独立可测。
 *
 * <p><b>三档模式</b>（由 {@code rag.business.retrieval-mode} 配置）：
 * <ul>
 *   <li>{@code vector} —— 纯向量召回，等价于直接调 {@link AiRagRetriever}</li>
 *   <li>{@code hybrid} —— 向量 + BM25，RRF 合并（需要 {@code rag.bm25.enabled=true}）</li>
 *   <li>{@code hybrid+rerank} —— hybrid 之后再用 cross-encoder 精排（额外需要 {@code rag.rerank.enabled=true}）</li>
 * </ul>
 *
 * <p><b>降级策略</b>（R8）：
 * <ul>
 *   <li>flag 关闭 → bean 为 null → 自动退化到 vector</li>
 *   <li>hybrid 调用抛异常 → catch 后降级回 vector，单条 query 不影响整条链路</li>
 *   <li>reranker 内部失败 → {@link Bge3Reranker#rerank} 自身返回 identity order，不抛异常</li>
 * </ul>
 *
 * <p><b>shopId filter 例外</b>：{@link #searchBlogReviewsByShop} 因 shopId metadata filter
 * 是业务强约束，不参与模式切换，恒走纯 vector。
 */
@Slf4j
@Component
public class BusinessRagRouter {

    private final AiRagRetriever vectorRetriever;

    private final HybridRagRetriever hybridRetriever;

    private final Bge3Reranker reranker;

    @Value("${rag.business.retrieval-mode:vector}")
    private String retrievalMode;

    @Value("${rag.hybrid.fetch-k:20}")
    private int rerankFetchK;

    @Value("${ai.agent.rag.top-k:5}")
    private int defaultTopK;

    public BusinessRagRouter(AiRagRetriever vectorRetriever,
                             @Autowired(required = false) HybridRagRetriever hybridRetriever,
                             @Autowired(required = false) Bge3Reranker reranker) {
        this.vectorRetriever = vectorRetriever;
        this.hybridRetriever = hybridRetriever;
        this.reranker = reranker;
    }

    // ============================ public API（DianPingAgentTools 调）============================

    public List<KnowledgeHitDTO> searchKnowledge(String query) {
        int finalTopK = defaultTopK;
        int fetchK = useRerank() ? rerankFetchK : finalTopK;
        List<KnowledgeHitDTO> hits;
        if (useHybrid()) {
            try {
                hits = hybridRetriever.hybridSearchKnowledge(query, fetchK);
            }
            catch (Exception e) {
                log.warn("[router] hybrid knowledge failed, fallback to vector: {}", e.toString());
                hits = vectorRetriever.searchKnowledge(query);
            }
        }
        else {
            hits = vectorRetriever.searchKnowledge(query);
        }
        if (useRerank() && hits != null && hits.size() > 1) {
            hits = applyRerank(query, hits, this::knowledgeToText, finalTopK);
        }
        return hits == null ? List.of() : hits;
    }

    public List<ShopToolDTO> searchShopProfiles(String query, Integer topK) {
        int finalTopK = normalizeTopK(topK);
        int fetchK = useRerank() ? rerankFetchK : finalTopK;
        List<ShopToolDTO> hits;
        if (useHybrid()) {
            try {
                hits = hybridRetriever.hybridSearchShops(query, fetchK);
            }
            catch (Exception e) {
                log.warn("[router] hybrid shop failed, fallback to vector: {}", e.toString());
                hits = vectorRetriever.searchShopProfiles(query, finalTopK);
            }
        }
        else {
            hits = vectorRetriever.searchShopProfiles(query, finalTopK);
        }
        if (useRerank() && hits != null && hits.size() > 1) {
            hits = applyRerank(query, hits, this::shopToText, finalTopK);
        }
        return hits == null ? List.of() : hits;
    }

    public List<BlogVectorHitDTO> searchBlogReviews(String query, Integer topK) {
        int finalTopK = normalizeTopK(topK);
        int fetchK = useRerank() ? rerankFetchK : finalTopK;
        List<BlogVectorHitDTO> hits;
        if (useHybrid()) {
            try {
                hits = hybridRetriever.hybridSearchReviews(query, fetchK);
            }
            catch (Exception e) {
                log.warn("[router] hybrid review failed, fallback to vector: {}", e.toString());
                hits = vectorRetriever.searchBlogReviews(query, finalTopK);
            }
        }
        else {
            hits = vectorRetriever.searchBlogReviews(query, finalTopK);
        }
        if (useRerank() && hits != null && hits.size() > 1) {
            hits = applyRerank(query, hits, this::reviewToText, finalTopK);
        }
        return hits == null ? List.of() : hits;
    }

    /**
     * shopId filter 是强业务约束，跳过 hybrid，恒走 vector。
     */
    public List<BlogVectorHitDTO> searchBlogReviewsByShop(Long shopId, String query, Integer topK) {
        return vectorRetriever.searchBlogReviewsByShop(shopId, query, normalizeTopK(topK));
    }

    // ============================ dispatch helpers ============================

    private boolean useHybrid() {
        return ("hybrid".equalsIgnoreCase(retrievalMode) || "hybrid+rerank".equalsIgnoreCase(retrievalMode))
                && hybridRetriever != null;
    }

    private boolean useRerank() {
        return "hybrid+rerank".equalsIgnoreCase(retrievalMode)
                && reranker != null
                && hybridRetriever != null;
    }

    private int normalizeTopK(Integer topK) {
        return (topK == null || topK <= 0) ? defaultTopK : topK;
    }

    private <T> List<T> applyRerank(String query, List<T> docs, Function<T, String> textExtractor, int topK) {
        List<String> texts = new ArrayList<>(docs.size());
        for (T d : docs) {
            texts.add(textExtractor.apply(d));
        }
        List<Integer> order = reranker.rerank(query, texts, topK);
        List<T> out = new ArrayList<>(order.size());
        for (Integer idx : order) {
            if (idx >= 0 && idx < docs.size()) {
                out.add(docs.get(idx));
            }
        }
        return out;
    }

    // ============================ text extractors（cross-encoder 友好）============================

    private String shopToText(ShopToolDTO s) {
        StringBuilder sb = new StringBuilder();
        if (s.getName() != null && !s.getName().isEmpty()) sb.append(s.getName()).append(' ');
        if (s.getArea() != null && !s.getArea().isEmpty()) sb.append(s.getArea()).append(' ');
        if (s.getAddress() != null && !s.getAddress().isEmpty()) sb.append(s.getAddress()).append(' ');
        if (s.getAvgPrice() != null) sb.append("人均").append(s.getAvgPrice()).append("元 ");
        if (s.getRating() != null) sb.append("评分").append(s.getRating()).append(' ');
        if (s.getOpenHours() != null && !s.getOpenHours().isEmpty()) sb.append("营业时间 ").append(s.getOpenHours()).append(' ');
        return sb.toString().trim();
    }

    private String reviewToText(BlogVectorHitDTO b) {
        StringBuilder sb = new StringBuilder();
        if (b.getTitle() != null && !b.getTitle().isEmpty()) sb.append(b.getTitle()).append(' ');
        if (b.getContent() != null && !b.getContent().isEmpty()) sb.append(b.getContent());
        return sb.toString().trim();
    }

    private String knowledgeToText(KnowledgeHitDTO k) {
        return k.getContent() == null ? "" : k.getContent();
    }
}
