package com.cityaihub.ai.rag.eval;

import com.cityaihub.ai.dto.BlogVectorHitDTO;
import com.cityaihub.ai.dto.KnowledgeHitDTO;
import com.cityaihub.ai.dto.ShopToolDTO;
import com.cityaihub.ai.rag.dto.EvalQuery;
import com.cityaihub.ai.rag.retriever.AiRagRetriever;
import com.cityaihub.ai.rag.retriever.Bge3Reranker;
import com.cityaihub.ai.rag.retriever.HybridRagRetriever;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Plan D：纯净 RAG 生成链路 —— query → AiRagRetriever 检索 → 拼 prompt → 业务 ChatModel 生成 answer。
 *
 * <p><b>不复用业务 ManualToolAgentLoopExecutor 的原因</b>：那是个动态 agent loop（自主决定调不调工具），
 * system prompt 里写着"先判断问题是否真的需要工具"，部分 query 可能跳过 RAG 直接用模型常识答 → 评估的就不再是
 * "RAG → generation"链路。Plan D 强制走 RAG，每条 query 必有 contexts。
 *
 * <p><b>复用 AiRagRetriever 的考量</b>：评估的是"业务真实生成质量"，包括业务现有的 similarity threshold、
 * 关键词回退、去重等后处理。如 ShopToolDTO 字段不足导致生成质量低，那也是评估要反映的真问题。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "rag.eval.generation.enabled", havingValue = "true")
public class RagAnswerGenerator {

    private static final String PROMPT_TEMPLATE =
            "你是 RAG 系统的回答模块。请严格基于下方提供的「上下文片段」回答用户问题。\n" +
            "\n" +
            "要求：\n" +
            "1. 答案必须能从上下文中找到依据，禁止编造上下文之外的信息\n" +
            "2. 如果上下文不足以回答问题，明说「根据现有上下文，无法完整回答」\n" +
            "3. 答案保持简洁（80~200 字），不要展开成长文\n" +
            "4. 优先列点说明，不要拼接连续长句\n" +
            "\n" +
            "上下文片段：\n" +
            "{contexts}\n" +
            "\n" +
            "用户问题：{query}\n" +
            "\n" +
            "请基于上述上下文回答：";

    private static final String EMPTY_CONTEXT_MARKER = "（无可用上下文：检索未返回任何相关文档）";

    private final ChatModel businessChatModel;     // 业务 autoconfigured ChatModel = Qwen2.5-7B
    private final AiRagRetriever aiRagRetriever;

    /**
     * Plan E 注入：hybrid 模式下用，bm25.enabled=false 时为 null（自动 fallback 到 vector 模式）。
     */
    @Autowired(required = false)
    private HybridRagRetriever hybridRetriever;

    /**
     * Plan G 注入：cross-encoder reranker，rerank.enabled=false 时为 null（hybrid+rerank 模式自动降级回 hybrid）。
     */
    @Autowired(required = false)
    private Bge3Reranker reranker;

    @Value("${rag.eval.generation.retrieval-mode:vector}")
    private String retrievalMode;     // vector | hybrid | hybrid+rerank

    @Value("${rag.eval.generation.top-k:5}")
    private int topK;

    /**
     * Plan G：hybrid+rerank 模式下，先用 hybrid 拿 fetchK 候选，再交 reranker 精排取 topK。
     * fetchK 越大候选多样性越高但每次 rerank 调用越慢；20 是 cross-encoder 业界默认。
     */
    @Value("${rag.hybrid.fetch-k:20}")
    private int rerankFetchK;

    @Value("${rag.eval.generation.generation-temperature:0.0}")
    private double generationTemperature;

    @Value("${rag.eval.generation.generation-max-tokens:400}")
    private int generationMaxTokens;

    @Value("${rag.eval.generation.context-truncate:200}")
    private int contextTruncate;

    public RagAnswerGenerator(ChatModel businessChatModel, AiRagRetriever aiRagRetriever) {
        this.businessChatModel = businessChatModel;
        this.aiRagRetriever = aiRagRetriever;
    }

    /**
     * 对一条评估 query 跑完整 RAG 生成。检索 / 生成失败时不抛异常，由 GenerationEvaluator 决定降级策略。
     */
    public RagAnswerOutput generate(EvalQuery q) {
        long retrievalStart = System.currentTimeMillis();
        List<String> contextLines = retrieveContexts(q);
        long retrievalMs = System.currentTimeMillis() - retrievalStart;

        String contextsBlock = contextLines.isEmpty()
                ? EMPTY_CONTEXT_MARKER
                : String.join("\n\n", contextLines);

        String prompt = PROMPT_TEMPLATE
                .replace("{contexts}", contextsBlock)
                .replace("{query}", q.getQuery());

        long generationStart = System.currentTimeMillis();
        String answer;
        try {
            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .temperature(generationTemperature)
                    .maxTokens(generationMaxTokens)
                    .build();
            answer = businessChatModel.call(new Prompt(new UserMessage(prompt), options))
                    .getResult().getOutput().getText();
            if (answer == null) {
                answer = "";
            }
        } catch (Exception e) {
            log.warn("[gen] business chat failed for query_id={} query='{}': {}",
                    q.getQueryId(), truncate(q.getQuery(), 60), e.toString());
            answer = "";
        }
        long generationMs = System.currentTimeMillis() - generationStart;

        return new RagAnswerOutput(q.getQueryId(), q.getQuery(), q.getTargetCollection(),
                contextsBlock, contextLines.size(), answer.trim(), retrievalMs, generationMs);
    }

    private List<String> retrieveContexts(EvalQuery q) {
        String collection = q.getTargetCollection();
        boolean useHybrid = ("hybrid".equalsIgnoreCase(retrievalMode)
                || "hybrid+rerank".equalsIgnoreCase(retrievalMode)) && hybridRetriever != null;
        boolean useRerank = "hybrid+rerank".equalsIgnoreCase(retrievalMode) && reranker != null && useHybrid;
        // hybrid+rerank：拿 rerankFetchK (默认 20) 候选给 reranker 精排
        int fetchK = useRerank ? rerankFetchK : topK;
        try {
            switch (collection) {
                case "shop_profile_vector": {
                    List<ShopToolDTO> hits = useHybrid
                            ? hybridRetriever.hybridSearchShops(q.getQuery(), fetchK)
                            : aiRagRetriever.searchShopProfiles(q.getQuery(), topK);
                    if (useRerank) hits = applyRerank(q.getQuery(), hits, this::shopToText);
                    return formatShops(hits);
                }
                case "blog_review_vector": {
                    List<BlogVectorHitDTO> hits = useHybrid
                            ? hybridRetriever.hybridSearchReviews(q.getQuery(), fetchK)
                            : aiRagRetriever.searchBlogReviews(q.getQuery(), topK);
                    if (useRerank) hits = applyRerank(q.getQuery(), hits, this::reviewToText);
                    return formatReviews(hits);
                }
                case "knowledge_vector": {
                    List<KnowledgeHitDTO> hits = useHybrid
                            ? hybridRetriever.hybridSearchKnowledge(q.getQuery(), fetchK)
                            : aiRagRetriever.searchKnowledge(q.getQuery());
                    if (useRerank) hits = applyRerank(q.getQuery(), hits, this::knowledgeToText);
                    return formatKnowledge(hits);
                }
                default:
                    log.warn("[gen] unknown target_collection: {}", collection);
                    return List.of();
            }
        } catch (Exception e) {
            log.warn("[gen] retrieve failed for query_id={} target={} mode={}: {}",
                    q.getQueryId(), collection, retrievalMode, e.toString());
            return List.of();
        }
    }

    /**
     * Plan G：调 reranker 精排，按返回的索引顺序重新组织 docs，截到 topK。
     * Reranker 内部失败会返回 identity order（原序），所以这里不用单独 try/catch。
     */
    private <T> List<T> applyRerank(String query, List<T> docs, Function<T, String> textExtractor) {
        if (docs == null || docs.size() <= 1) return docs;
        List<String> texts = new ArrayList<>(docs.size());
        for (T d : docs) texts.add(textExtractor.apply(d));
        List<Integer> order = reranker.rerank(query, texts, topK);
        List<T> out = new ArrayList<>(order.size());
        for (Integer idx : order) {
            if (idx >= 0 && idx < docs.size()) out.add(docs.get(idx));
        }
        return out;
    }

    /**
     * 把 ShopToolDTO 拼成 cross-encoder 友好的自然语言描述（结构化字段串成短语）。
     * 注意 ShopToolDTO 字段：name / area / address / avgPrice / rating（无 category 字段，typeId 是数字 cross-encoder 看不懂，跳过）
     */
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
        return b.getContent() == null ? "" : b.getContent();
    }

    private String knowledgeToText(KnowledgeHitDTO k) {
        return k.getContent() == null ? "" : k.getContent();
    }

    /** Plan E：暴露当前模式，给 EvalRunnerTest 在双轮跑时可以临时切换 */
    public String getRetrievalMode() {
        return retrievalMode;
    }

    public void setRetrievalMode(String mode) {
        this.retrievalMode = mode;
    }

    private List<String> formatShops(List<ShopToolDTO> shops) {
        List<String> out = new ArrayList<>(shops.size());
        for (int i = 0; i < shops.size() && i < topK; i++) {
            ShopToolDTO s = shops.get(i);
            String area = s.getArea() == null ? "" : s.getArea();
            String addr = s.getAddress() == null ? "" : s.getAddress();
            String price = s.getAvgPrice() == null ? "" : ("人均" + s.getAvgPrice() + "元");
            String rating = s.getRating() == null ? "" : ("评分" + s.getRating());
            String header = String.format("【店铺%d】%s（%s%s%s%s）",
                    i + 1, nullSafe(s.getName()), area, addr.isEmpty() ? "" : "/" + addr,
                    price.isEmpty() ? "" : "/" + price, rating.isEmpty() ? "" : "/" + rating);
            out.add(truncate(header, 300));
        }
        return out;
    }

    private List<String> formatReviews(List<BlogVectorHitDTO> reviews) {
        List<String> out = new ArrayList<>(reviews.size());
        for (int i = 0; i < reviews.size() && i < topK; i++) {
            BlogVectorHitDTO b = reviews.get(i);
            out.add(String.format("【评论%d】(shop_id=%s) %s",
                    i + 1, b.getShopId(), truncate(nullSafe(b.getContent()), contextTruncate)));
        }
        return out;
    }

    private List<String> formatKnowledge(List<KnowledgeHitDTO> hits) {
        List<String> out = new ArrayList<>(hits.size());
        for (int i = 0; i < hits.size() && i < topK; i++) {
            KnowledgeHitDTO k = hits.get(i);
            out.add(String.format("【知识%d】%s",
                    i + 1, truncate(nullSafe(k.getContent()), contextTruncate)));
        }
        return out;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    @Data
    @AllArgsConstructor
    public static final class RagAnswerOutput {
        public final int queryId;
        public final String query;
        public final String targetCollection;
        public final String contexts;        // 完整拼接好的 contexts（含 EMPTY_CONTEXT_MARKER 兜底）
        public final int contextCount;       // retrieved 文档数
        public final String answer;
        public final long retrievalLatencyMs;
        public final long generationLatencyMs;

        public boolean hasContexts() {
            return contextCount > 0;
        }

        public boolean hasAnswer() {
            return answer != null && !answer.isBlank();
        }
    }
}
