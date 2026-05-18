package com.cityaihub.ai.rag.ingest;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.GetCollectionStatisticsResponse;
import io.milvus.param.collection.GetCollectionStatisticsParam;
import io.milvus.response.GetCollStatResponseWrapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * RAG 灌库入口测试。
 *
 * <p>⚠️ 本测试需要本地启动 MySQL + Redis + Milvus + 硅基流动 API key 配置后手动跑：
 * <pre>
 *   mvn test -Dtest=IngestRunnerTest#ingestAll
 *   mvn test -Dtest=IngestRunnerTest#ingestShopOnly
 *   mvn test -Dtest=IngestRunnerTest#ingestReviewOnly
 *   mvn test -Dtest=IngestRunnerTest#ingestKnowledgeOnly
 * </pre>
 *
 * <p>因为依赖外部服务，默认 @Disabled 不参与 mvn test 全量执行；
 * 需要灌库时通过 -Dtest=... 显式触发，运行时手动注释掉 @Disabled 一次即可。
 */
@Slf4j
@Disabled("Requires local MySQL/Redis/Milvus + API key. Run manually with -Dtest=IngestRunnerTest#xxx")
@SpringBootTest(properties = "ai.agent.bootstrap.enabled=false")
class IngestRunnerTest {

    @Autowired
    private JsonlIngestService ingestService;

    @Autowired
    private MilvusServiceClient milvusClient;

    /** Step C：只查 collection 当前条数，不灌库。验证数据完整性用。 */
    @Test
    void countEntities() {
        for (String c : new String[]{"shop_profile_vector", "blog_review_vector", "knowledge_vector"}) {
            try {
                GetCollectionStatisticsResponse resp = milvusClient.getCollectionStatistics(
                        GetCollectionStatisticsParam.newBuilder()
                                .withCollectionName(c)
                                .build()).getData();
                long n = new GetCollStatResponseWrapper(resp).getRowCount();
                log.info("[count] {} = {} entities", c, n);
            } catch (Exception e) {
                log.warn("[count] {} → {}", c, e.toString());
            }
        }
    }

    @Test
    void ingestAll() {
        IngestStat shop = ingestService.ingestShopProfile();
        log.info("Shop done: {}", shop);

        IngestStat review = ingestService.ingestReviews();
        log.info("Review done: {}", review);

        IngestStat knowledge = ingestService.ingestKnowledge();
        log.info("Knowledge done: {}", knowledge);

        log.info("=== INGEST SUMMARY ===");
        log.info("Shop:      {}/{}  ({}%)", shop.getSucceeded(), shop.getTotal(),
                (int) (shop.successRate() * 100));
        log.info("Review:    {}/{}  ({}%)", review.getSucceeded(), review.getTotal(),
                (int) (review.successRate() * 100));
        log.info("Knowledge: {}/{}  ({}%)", knowledge.getSucceeded(), knowledge.getTotal(),
                (int) (knowledge.successRate() * 100));
    }

    @Test
    void ingestShopOnly() {
        IngestStat stat = ingestService.ingestShopProfile();
        log.info("Shop ingest stat: {}", stat);
    }

    @Test
    void ingestReviewOnly() {
        IngestStat stat = ingestService.ingestReviews();
        log.info("Review ingest stat: {}", stat);
    }

    @Test
    void ingestKnowledgeOnly() {
        IngestStat stat = ingestService.ingestKnowledge();
        log.info("Knowledge ingest stat: {}", stat);
    }
}
