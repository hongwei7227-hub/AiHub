package com.hmdp.ai.rag.retriever;

import com.hmdp.ai.dto.BlogVectorHitDTO;
import com.hmdp.ai.dto.KnowledgeHitDTO;
import com.hmdp.ai.dto.ShopToolDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BusinessRagRouterTest {

    @Mock
    private AiRagRetriever vectorRetriever;

    @Mock
    private HybridRagRetriever hybridRetriever;

    @Mock
    private Bge3Reranker reranker;

    private BusinessRagRouter newRouter(String mode, HybridRagRetriever hybrid, Bge3Reranker rerank) {
        BusinessRagRouter router = new BusinessRagRouter(vectorRetriever, hybrid, rerank);
        ReflectionTestUtils.setField(router, "retrievalMode", mode);
        ReflectionTestUtils.setField(router, "rerankFetchK", 20);
        ReflectionTestUtils.setField(router, "defaultTopK", 5);
        return router;
    }

    private List<ShopToolDTO> buildShopHits(int n) {
        List<ShopToolDTO> hits = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ShopToolDTO dto = new ShopToolDTO();
            dto.setShopId((long) (i + 1));
            dto.setName("shop_" + i);
            hits.add(dto);
        }
        return hits;
    }

    // ============================ vector 模式 ============================

    @Test
    void vectorMode_shouldCallVectorRetrieverDirectly_forShops() {
        BusinessRagRouter router = newRouter("vector", hybridRetriever, reranker);
        when(vectorRetriever.searchShopProfiles("query", 5)).thenReturn(buildShopHits(3));

        List<ShopToolDTO> result = router.searchShopProfiles("query", 5);

        assertEquals(3, result.size());
        verify(vectorRetriever).searchShopProfiles("query", 5);
        verifyNoInteractions(hybridRetriever, reranker);
    }

    // ============================ hybrid 模式 ============================

    @Test
    void hybridMode_shouldCallHybridRetriever_forShops() {
        BusinessRagRouter router = newRouter("hybrid", hybridRetriever, reranker);
        when(hybridRetriever.hybridSearchShops("query", 5)).thenReturn(buildShopHits(3));

        List<ShopToolDTO> result = router.searchShopProfiles("query", 5);

        assertEquals(3, result.size());
        verify(hybridRetriever).hybridSearchShops("query", 5);
        verify(vectorRetriever, never()).searchShopProfiles(anyString(), anyInt());
        verifyNoInteractions(reranker);
    }

    // ============================ hybrid+rerank 模式 ============================

    @Test
    void hybridRerankMode_shouldFetchK20AndApplyRerank_forShops() {
        BusinessRagRouter router = newRouter("hybrid+rerank", hybridRetriever, reranker);
        when(hybridRetriever.hybridSearchShops("query", 20)).thenReturn(buildShopHits(10));
        // reranker 返回索引 [2, 0, 1] 表示原列表第 2/0/1 项是 top3
        when(reranker.rerank(eq("query"), any(), eq(5))).thenReturn(List.of(2, 0, 1));

        List<ShopToolDTO> result = router.searchShopProfiles("query", 5);

        assertEquals(3, result.size());
        assertEquals("shop_2", result.get(0).getName());
        assertEquals("shop_0", result.get(1).getName());
        assertEquals("shop_1", result.get(2).getName());
        verify(hybridRetriever).hybridSearchShops("query", 20);  // fetchK=20 而非 topK=5
        verify(reranker).rerank(eq("query"), any(), eq(5));
    }

    // ============================ Q1 修订:bean null 时 fallback ============================

    @Test
    void hybridMode_shouldFallbackToVectorWhenHybridBeanIsNull() {
        BusinessRagRouter router = newRouter("hybrid", null, null);  // hybrid bean 不存在
        when(vectorRetriever.searchShopProfiles("query", 5)).thenReturn(buildShopHits(3));

        List<ShopToolDTO> result = router.searchShopProfiles("query", 5);

        assertEquals(3, result.size());
        verify(vectorRetriever).searchShopProfiles("query", 5);
    }

    @Test
    void hybridRerankMode_shouldDegradeToHybridWhenRerankerBeanIsNull() {
        BusinessRagRouter router = newRouter("hybrid+rerank", hybridRetriever, null);  // reranker bean 不存在
        when(hybridRetriever.hybridSearchShops("query", 5)).thenReturn(buildShopHits(3));

        List<ShopToolDTO> result = router.searchShopProfiles("query", 5);

        assertEquals(3, result.size());
        verify(hybridRetriever).hybridSearchShops("query", 5);  // fetchK 退回 topK,因为 useRerank=false
    }

    // ============================ R8 修订:hybrid 抛异常时 fallback ============================

    @Test
    void hybridMode_shouldCatchHybridExceptionAndFallbackToVector() {
        BusinessRagRouter router = newRouter("hybrid", hybridRetriever, reranker);
        when(hybridRetriever.hybridSearchShops("query", 5)).thenThrow(new RuntimeException("BM25 index unavailable"));
        when(vectorRetriever.searchShopProfiles("query", 5)).thenReturn(buildShopHits(2));

        List<ShopToolDTO> result = router.searchShopProfiles("query", 5);

        assertEquals(2, result.size(), "hybrid 抛异常应该 fallback 到 vector");
        verify(hybridRetriever).hybridSearchShops("query", 5);
        verify(vectorRetriever).searchShopProfiles("query", 5);
    }

    // ============================ shopId filter 例外:任何 mode 都走 vector ============================

    @Test
    void searchBlogReviewsByShop_shouldAlwaysCallVectorEvenInHybridRerankMode() {
        BusinessRagRouter router = newRouter("hybrid+rerank", hybridRetriever, reranker);
        when(vectorRetriever.searchBlogReviewsByShop(123L, "query", 5)).thenReturn(List.of(new BlogVectorHitDTO()));

        List<BlogVectorHitDTO> result = router.searchBlogReviewsByShop(123L, "query", 5);

        assertEquals(1, result.size());
        verify(vectorRetriever).searchBlogReviewsByShop(123L, "query", 5);
        verifyNoInteractions(hybridRetriever, reranker);
    }

    // ============================ searchKnowledge 用 defaultTopK ============================

    @Test
    void searchKnowledge_shouldUseDefaultTopK() {
        BusinessRagRouter router = newRouter("hybrid", hybridRetriever, reranker);
        List<KnowledgeHitDTO> hits = new ArrayList<>();
        hits.add(new KnowledgeHitDTO());
        when(hybridRetriever.hybridSearchKnowledge("query", 5)).thenReturn(hits);

        List<KnowledgeHitDTO> result = router.searchKnowledge("query");

        assertEquals(1, result.size());
        verify(hybridRetriever).hybridSearchKnowledge("query", 5);  // defaultTopK=5
    }

    // ============================ topK null 时用 defaultTopK ============================

    @Test
    void searchShopProfiles_shouldUseDefaultTopKWhenNullPassed() {
        BusinessRagRouter router = newRouter("vector", hybridRetriever, reranker);
        when(vectorRetriever.searchShopProfiles("query", 5)).thenReturn(buildShopHits(2));

        List<ShopToolDTO> result = router.searchShopProfiles("query", null);

        assertEquals(2, result.size());
        verify(vectorRetriever).searchShopProfiles("query", 5);
    }

    // ============================ 单条结果不触发 rerank ============================

    @Test
    void hybridRerankMode_shouldSkipRerankWhenOnlyOneHit() {
        BusinessRagRouter router = newRouter("hybrid+rerank", hybridRetriever, reranker);
        when(hybridRetriever.hybridSearchShops("query", 20)).thenReturn(buildShopHits(1));

        List<ShopToolDTO> result = router.searchShopProfiles("query", 5);

        assertEquals(1, result.size());
        verify(reranker, never()).rerank(anyString(), any(), anyInt());
    }

    // ============================ reviews 路径 sanity check ============================

    @Test
    void hybridMode_shouldCallHybridRetriever_forReviews() {
        BusinessRagRouter router = newRouter("hybrid", hybridRetriever, reranker);
        when(hybridRetriever.hybridSearchReviews("query", 5)).thenReturn(List.of(new BlogVectorHitDTO()));

        List<BlogVectorHitDTO> result = router.searchBlogReviews("query", 5);

        assertEquals(1, result.size());
        verify(hybridRetriever).hybridSearchReviews("query", 5);
    }
}
