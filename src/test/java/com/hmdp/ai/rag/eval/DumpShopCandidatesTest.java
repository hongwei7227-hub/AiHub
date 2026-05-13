package com.hmdp.ai.rag.eval;

import com.hmdp.ai.rag.dto.EvalQuery;
import com.hmdp.ai.rag.ingest.JsonlReader;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Plan J 后续: 给 60 条 shop_profile_vector eval query 抽 top-30 候选, dump 到 markdown.
 * 后续由 Claude Opus 4.7 (当前 Claude Code session) 当裁判选 top-5 当新 ground truth.
 *
 * 输出: docs/shop_profile_candidates.md
 *
 * 跑法 (临时去掉 @Disabled):
 *   mvn test -Dtest=DumpShopCandidatesTest#dumpCandidates
 */
@Slf4j
// @Disabled  // Plan J 后续临时启用
@SpringBootTest(properties = {
        "ai.agent.bootstrap.enabled=false",
        "rag.eval.llm-judge.enabled=false",
        "rag.eval.generation.enabled=false",
        "ai.agent.rag.similarity-threshold=0.5",
})
class DumpShopCandidatesTest {

    private static final int TOP_K = 30;

    @Autowired @Qualifier("shopProfileVectorStore")
    private VectorStore shopProfileVectorStore;

    @Autowired private JsonlReader jsonlReader;

    @Value("${rag.ingest.data-dir}") private String dataDir;
    @Value("${rag.eval.output-dir}") private String outputDir;

    @Test
    void dumpCandidates() throws Exception {
        Path evalFile = Path.of(dataDir, "eval_queries.jsonl");
        List<EvalQuery> queries = jsonlReader.readAll(evalFile, EvalQuery.class);
        List<EvalQuery> shopQueries = queries.stream()
                .filter(q -> "shop_profile_vector".equals(q.getTargetCollection()))
                .collect(Collectors.toList());
        log.info("[dump] loaded {} shop_profile queries from {}", shopQueries.size(), evalFile);

        StringBuilder sb = new StringBuilder(50_000);
        sb.append("# shop_profile_vector eval queries: top-30 候选 (Plan J 后续 LLM 标 GT)\n\n");
        sb.append("生成方式: embedding 召回 topK=").append(TOP_K)
                .append(", threshold=0.0, 玉泉 100001-100999 已排除\n\n");

        for (EvalQuery q : shopQueries) {
            int searchTopK = TOP_K * 3;
            SearchRequest req = SearchRequest.builder()
                    .query(q.getQuery())
                    .topK(searchTopK)
                    .similarityThreshold(0.0)
                    .build();
            List<Document> hits = shopProfileVectorStore.similaritySearch(req);
            if (hits == null) hits = List.of();
            hits = hits.stream()
                    .filter(d -> !RecallEvaluator.isYuquanDemo(RecallEvaluator.extractBusinessId(d)))
                    .limit(TOP_K)
                    .collect(Collectors.toList());

            sb.append("## query_id=").append(q.getQueryId())
                    .append(": ").append(q.getQuery()).append("\n\n");
            sb.append("**当前 ground truth**: ").append(q.getRelevantIds()).append("\n\n");
            sb.append("**top-").append(TOP_K).append(" 候选**:\n\n");
            for (int i = 0; i < hits.size(); i++) {
                Document d = hits.get(i);
                String bizId = RecallEvaluator.extractBusinessId(d);
                String name = getMeta(d, "shopName");
                String addr = getMeta(d, "address");
                Double sim = getSimilarity(d);
                String content = d.getText();
                String preview = content == null ? "" : content.replace("\n", " ");
                if (preview.length() > 120) preview = preview.substring(0, 120);
                sb.append(String.format("%d. shop_id=%s  sim=%.3f  name=\"%s\"  addr=\"%s\"  preview=\"%s\"%n",
                        i + 1, bizId, sim != null ? sim : 0.0, name, addr, preview));
            }
            sb.append("\n---\n\n");
        }

        Path outFile = Path.of(outputDir).resolve("shop_profile_candidates.md");
        Files.createDirectories(outFile.getParent());
        Files.writeString(outFile, sb.toString());
        log.info("[dump] wrote {} ({} KB) for {} queries", outFile, sb.length() / 1024, shopQueries.size());
    }

    private static String getMeta(Document d, String key) {
        if (d.getMetadata() == null) return "";
        Object v = d.getMetadata().get(key);
        return v == null ? "" : v.toString();
    }

    private static Double getSimilarity(Document d) {
        if (d.getMetadata() == null) return null;
        Object v = d.getMetadata().get("distance");
        if (v instanceof Number) return ((Number) v).doubleValue();
        return null;
    }
}
