package com.hmdp.ai.rag.monitor;

import com.hmdp.ai.rag.retriever.Bge3Reranker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RerankStatsEndpointTest {

    @Mock
    private Bge3Reranker reranker;

    @Test
    void shouldReturnDisabledWhenRerankerBeanIsNull() {
        RerankStatsEndpoint endpoint = new RerankStatsEndpoint(null);

        Map<String, Object> stats = endpoint.stats();

        assertEquals(false, stats.get("enabled"));
        assertTrue(stats.containsKey("note"), "disabled 时应该有 note 字段说明原因");
        assertFalse(stats.containsKey("callCount"), "disabled 时不应有 callCount");
    }

    @Test
    void shouldReturnFullStatsWhenRerankerBeanExists() {
        when(reranker.callCount()).thenReturn(142L);
        when(reranker.failureCount()).thenReturn(3L);
        when(reranker.avgLatencyMs()).thenReturn(287.5);
        RerankStatsEndpoint endpoint = new RerankStatsEndpoint(reranker);

        Map<String, Object> stats = endpoint.stats();

        assertEquals(true, stats.get("enabled"));
        assertEquals(142L, stats.get("callCount"));
        assertEquals(3L, stats.get("failureCount"));
        assertEquals(3.0 / 142.0, (Double) stats.get("failureRate"), 1e-9);
        assertEquals(287.5, stats.get("avgLatencyMs"));
    }

    @Test
    void shouldReturnZeroFailureRateWhenNoCallsYet() {
        when(reranker.callCount()).thenReturn(0L);
        when(reranker.failureCount()).thenReturn(0L);
        when(reranker.avgLatencyMs()).thenReturn(0.0);
        RerankStatsEndpoint endpoint = new RerankStatsEndpoint(reranker);

        Map<String, Object> stats = endpoint.stats();

        assertEquals(true, stats.get("enabled"));
        assertEquals(0L, stats.get("callCount"));
        assertEquals(0.0, stats.get("failureRate"), "0 调用时不能除以零导致 NaN");
    }
}
