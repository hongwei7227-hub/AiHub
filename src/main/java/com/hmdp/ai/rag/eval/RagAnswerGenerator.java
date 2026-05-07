package com.hmdp.ai.rag.eval;

import com.hmdp.ai.dto.BlogVectorHitDTO;
import com.hmdp.ai.dto.KnowledgeHitDTO;
import com.hmdp.ai.dto.ShopToolDTO;
import com.hmdp.ai.rag.dto.EvalQuery;
import com.hmdp.ai.rag.retriever.AiRagRetriever;
import com.hmdp.ai.rag.retriever.HybridRagRetriever;
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

    @Value("${rag.eval.generation.retrieval-mode:vector}")
    private String retrievalMode;     // vector | hybrid | hyde-hybrid

    private static final String HYDE_PROMPT_TEMPLATE =
            "假装你是杭州本地美食专家。针对下面的问题，给一个简短的「理想答案示例」（80 字内，可以编造店铺名/菜品/价格，但要符合现实）。\n" +
            "\n" +
            "要求：\n" +
            "1. 不要解释，直接给答案示例\n" +
            "2. 包含具体店铺名 + 地段 + 价格 + 招牌菜（如果适用）\n" +
            "3. 80 字以内\n" +
            "\n" +
            "问题：{query}\n" +
            "\n" +
            "答案示例：";

    @Value("${rag.eval.generation.top-k:5}")
    private int topK;

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
        boolean useHybrid = "hybrid".equalsIgnoreCase(retrievalMode) && hybridRetriever != null;
        boolean useHyde = "hyde-hybrid".equalsIgnoreCase(retrievalMode) && hybridRetriever != null;

        // Plan F：HyDE 用业务 ChatModel 编一个"假想理想答案"，用它的 embedding 去检索（修复抽象 query → 具体 doc 的语义错配）
        // 重要：retrievalQuery 只用于检索阶段；最终生成 answer 的 prompt 里仍是原 q.getQuery()，HyDE 不污染生成
        String retrievalQuery = useHyde ? generateHypothetical(q) : q.getQuery();

        try {
            if (useHybrid || useHyde) {
                return switch (collection) {
                    case "shop_profile_vector" -> formatShops(hybridRetriever.hybridSearchShops(retrievalQuery, topK));
                    case "blog_review_vector" -> formatReviews(hybridRetriever.hybridSearchReviews(retrievalQuery, topK));
                    case "knowledge_vector" -> formatKnowledge(hybridRetriever.hybridSearchKnowledge(retrievalQuery, topK));
                    default -> {
                        log.warn("[gen] unknown target_collection: {}", collection);
                        yield List.of();
                    }
                };
            }
            return switch (collection) {
                case "shop_profile_vector" -> formatShops(aiRagRetriever.searchShopProfiles(q.getQuery(), topK));
                case "blog_review_vector" -> formatReviews(aiRagRetriever.searchBlogReviews(q.getQuery(), topK));
                case "knowledge_vector" -> formatKnowledge(aiRagRetriever.searchKnowledge(q.getQuery()));
                default -> {
                    log.warn("[gen] unknown target_collection: {}", collection);
                    yield List.of();
                }
            };
        } catch (Exception e) {
            log.warn("[gen] retrieve failed for query_id={} target={} mode={}: {}",
                    q.getQueryId(), collection, retrievalMode, e.toString());
            return List.of();
        }
    }

    /**
     * Plan F：HyDE - 让业务 ChatModel 生成一个假想理想答案，再用假想答案的 embedding 去检索。
     * 比起原始 query，假想答案在语义空间更靠近真正的相关 doc（论文 Gao et al. 2022）。
     * 失败兜底：返回原 query，不让 HyDE 故障导致整条 pipeline 挂掉。
     */
    private String generateHypothetical(EvalQuery q) {
        String prompt = HYDE_PROMPT_TEMPLATE.replace("{query}", q.getQuery());
        try {
            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .temperature(0.3)        // 给一点多样性，假想答案不能太死板
                    .maxTokens(150)
                    .build();
            String text = businessChatModel.call(new Prompt(new UserMessage(prompt), options))
                    .getResult().getOutput().getText();
            String hyp = text == null ? "" : text.trim();
            if (hyp.isEmpty()) {
                log.warn("[hyde] empty hypothetical for query_id={} → fallback to original query", q.getQueryId());
                return q.getQuery();
            }
            log.debug("[hyde] qid={} q='{}' → hyp='{}'",
                    q.getQueryId(), truncate(q.getQuery(), 30), truncate(hyp, 80));
            return hyp;
        } catch (Exception e) {
            log.warn("[hyde] failed for query_id={}: {} → fallback to original query",
                    q.getQueryId(), e.toString());
            return q.getQuery();
        }
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
