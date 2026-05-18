package com.hmdp.ai.rag.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plan D：generation 层 LLM-as-judge —— 两个指标。
 *
 * <ol>
 *   <li><b>Faithfulness</b>(answer, contexts) → 0.0~1.0：answer 是否被 contexts 支持，越低 = 越可能编造
 *   <li><b>AnswerRelevancy</b>(query, answer) → true/false：answer 是否对 query 直接回答（即使不完整也算）
 * </ol>
 *
 * <p>独立 lazy-init OpenAiChatModel（指向 Plan C 的反代 = Claude Haiku 4.5），<b>不暴露成 Spring bean</b>——
 * 与 LlmJudge 同样规避业务 ChatModel 自动装配冲突。
 *
 * <p>配置项复用 {@code rag.eval.llm-judge.*}（base-url / api-key / model）：judge 模型对 retrieval 和
 * generation 评估应该用同一个，避免引入第二个评判主体。Plan D 自己的配置只控 truncate / retry / cache。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "rag.eval.generation.enabled", havingValue = "true")
public class GenerationJudge {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern CODE_FENCE = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCORE_REGEX = Pattern.compile("\"score\"\\s*:\\s*([0-9.]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern RELEVANT_REGEX = Pattern.compile("\"relevant\"\\s*:\\s*(true|false)", Pattern.CASE_INSENSITIVE);

    private ChatModel judgeChatModel;     // lazy init in @PostConstruct

    private final Map<String, FaithResult> faithCache = new ConcurrentHashMap<>();
    private final Map<String, RelevancyResult> relevancyCache = new ConcurrentHashMap<>();
    private final AtomicLong llmCalls = new AtomicLong();
    private final AtomicLong llmFailures = new AtomicLong();

    /** Faithfulness 单次判断结果。score=0 + reason="ALL_RETRIES_FAILED" 表示彻底失败。 */
    public static final class FaithResult {
        public final double score;
        public final String reason;
        public FaithResult(double score, String reason) {
            this.score = score;
            this.reason = reason == null ? "" : reason;
        }
    }

    /** Answer relevancy 单次判断结果。relevant=false + reason="ALL_RETRIES_FAILED" 表示彻底失败。 */
    public static final class RelevancyResult {
        public final boolean relevant;
        public final String reason;
        public RelevancyResult(boolean relevant, String reason) {
            this.relevant = relevant;
            this.reason = reason == null ? "" : reason;
        }
    }

    @Value("${rag.eval.llm-judge.base-url}")
    private String baseUrl;

    @Value("${rag.eval.llm-judge.api-key}")
    private String apiKey;

    @Value("${rag.eval.llm-judge.model}")
    private String judgeModel;

    @Value("${rag.eval.generation.max-retries:3}")
    private int maxRetries;

    @Value("${rag.eval.generation.retry-base-sleep-ms:1000}")
    private long retryBaseSleepMs;

    @Value("${rag.eval.generation.contexts-total-truncate:800}")
    private int contextsTotalTruncate;

    @Value("${rag.eval.generation.answer-truncate:400}")
    private int answerTruncate;

    @Value("${rag.eval.generation.cache-enabled:true}")
    private boolean cacheEnabled;

    @PostConstruct
    void init() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "rag.eval.llm-judge.api-key is blank. Set env LLM_JUDGE_API_KEY before running generation eval.");
        }
        log.info("[gen-judge] init judgeChatModel base-url={} model={} (apiKey hidden)", baseUrl, judgeModel);

        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();
        // maxTokens=400：score+reason 输出很短，400 足够。
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(judgeModel)
                .temperature(0.0)
                .maxTokens(400)
                .build();
        // 关掉 Spring AI 的 RetryTemplate（默认 5 次会让 503 hang 5+ 分钟），自己在 doWithRetry 里重试
        RetryTemplate noRetry = RetryTemplate.builder().maxAttempts(1).build();
        this.judgeChatModel = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .retryTemplate(noRetry)
                .build();
    }

    // ============================ Faithfulness ============================

    public FaithResult judgeFaithfulness(String answer, String contexts) {
        if (answer == null || answer.isBlank()) {
            return new FaithResult(0.0, "EMPTY_ANSWER");
        }
        if (!cacheEnabled) {
            return doFaithWithRetry(answer, contexts);
        }
        String key = "F||" + hash(answer) + "||" + hash(contexts);
        return faithCache.computeIfAbsent(key, k -> doFaithWithRetry(answer, contexts));
    }

    private FaithResult doFaithWithRetry(String answer, String contexts) {
        String prompt = buildFaithPrompt(answer, contexts);
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                llmCalls.incrementAndGet();
                ChatResponse resp = judgeChatModel.call(new Prompt(new UserMessage(prompt)));
                String text = resp.getResult().getOutput().getText();
                FaithResult parsed = parseFaith(text);
                if (parsed != null) {
                    return parsed;
                }
                log.warn("[gen-judge.faith] attempt {}/{} parse failed, raw='{}'",
                        attempt, maxRetries, truncate(text, 200));
            } catch (Exception e) {
                log.warn("[gen-judge.faith] attempt {}/{} call failed: {}",
                        attempt, maxRetries, e.toString());
            }
            if (attempt < maxRetries) sleepBackoff(attempt);
        }
        llmFailures.incrementAndGet();
        return new FaithResult(0.0, "ALL_RETRIES_FAILED");
    }

    private String buildFaithPrompt(String answer, String contexts) {
        return "你是 RAG 系统的忠实度评分员。判断\"模型生成的答案\"是否完全被\"上下文片段\"支持，没有编造。\n\n" +
                "不要做搜索，不要说\"我会搜索\"，直接基于给出的信息评分。\n\n" +
                "打分标准（0.0~1.0）：\n" +
                "- 1.0：答案中所有声明都能从上下文找到直接依据\n" +
                "- 0.7：答案中绝大部分声明有依据，少量轻微推断（合理引申）\n" +
                "- 0.4：答案部分内容超出上下文（明显推断/补充）\n" +
                "- 0.0：答案大部分凭空编造，与上下文无关\n\n" +
                "如果答案是\"根据现有上下文，无法完整回答\"这类承认信息不足的兜底回复，给 0.7（不算编造）。\n\n" +
                "输出严格 JSON：{\"score\": <0.0~1.0 浮点>, \"reason\": \"<不超过 20 字>\"}\n\n" +
                "上下文片段：\n" + truncate(contexts, contextsTotalTruncate) + "\n\n" +
                "模型生成的答案：\n" + truncate(answer, answerTruncate);
    }

    static FaithResult parseFaith(String text) {
        if (text == null || text.isBlank()) return null;
        FaithResult r = tryJsonFaith(text);
        if (r != null) return r;

        Matcher fence = CODE_FENCE.matcher(text);
        if (fence.find()) {
            r = tryJsonFaith(fence.group(1));
            if (r != null) return r;
        }
        int lb = text.indexOf('{');
        int rb = text.lastIndexOf('}');
        if (lb >= 0 && rb > lb) {
            r = tryJsonFaith(text.substring(lb, rb + 1));
            if (r != null) return r;
        }
        Matcher m = SCORE_REGEX.matcher(text);
        if (m.find()) {
            try {
                double s = clamp01(Double.parseDouble(m.group(1)));
                return new FaithResult(s, "regex-fallback");
            } catch (NumberFormatException ignore) {}
        }
        return null;
    }

    private static FaithResult tryJsonFaith(String s) {
        try {
            JsonNode obj = MAPPER.readTree(s.trim());
            if (!obj.has("score") || !obj.get("score").isNumber()) return null;
            double score = clamp01(obj.get("score").asDouble());
            String reason = obj.has("reason") ? obj.get("reason").asText("") : "";
            return new FaithResult(score, reason);
        } catch (Exception ignore) {
            return null;
        }
    }

    // ============================ AnswerRelevancy ============================

    public RelevancyResult judgeAnswerRelevancy(String query, String answer) {
        if (answer == null || answer.isBlank()) {
            return new RelevancyResult(false, "EMPTY_ANSWER");
        }
        if (!cacheEnabled) {
            return doRelevancyWithRetry(query, answer);
        }
        String key = "R||" + hash(query) + "||" + hash(answer);
        return relevancyCache.computeIfAbsent(key, k -> doRelevancyWithRetry(query, answer));
    }

    private RelevancyResult doRelevancyWithRetry(String query, String answer) {
        String prompt = buildRelevancyPrompt(query, answer);
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                llmCalls.incrementAndGet();
                ChatResponse resp = judgeChatModel.call(new Prompt(new UserMessage(prompt)));
                String text = resp.getResult().getOutput().getText();
                RelevancyResult parsed = parseRelevancy(text);
                if (parsed != null) {
                    return parsed;
                }
                log.warn("[gen-judge.rel] attempt {}/{} parse failed, raw='{}'",
                        attempt, maxRetries, truncate(text, 200));
            } catch (Exception e) {
                log.warn("[gen-judge.rel] attempt {}/{} call failed: {}",
                        attempt, maxRetries, e.toString());
            }
            if (attempt < maxRetries) sleepBackoff(attempt);
        }
        llmFailures.incrementAndGet();
        return new RelevancyResult(false, "ALL_RETRIES_FAILED");
    }

    private String buildRelevancyPrompt(String query, String answer) {
        return "你是 RAG 系统的相关性评分员。判断\"模型生成的答案\"是否对\"用户问题\"直接回答。\n\n" +
                "不要做搜索，不要说\"我会搜索\"，直接基于给出的信息评分。\n\n" +
                "判断标准：\n" +
                "- 直接回答用户问题（即使不完整也算）→ relevant=true\n" +
                "- 答非所问 / 答了别的话题 → relevant=false\n" +
                "- 答案是\"根据现有上下文，无法完整回答\"这类兜底回复 → relevant=false（没有真的回答）\n\n" +
                "输出严格 JSON：{\"relevant\": true 或 false, \"reason\": \"<不超过 15 字>\"}\n\n" +
                "用户问题：" + query + "\n\n" +
                "模型生成的答案：\n" + truncate(answer, answerTruncate);
    }

    static RelevancyResult parseRelevancy(String text) {
        if (text == null || text.isBlank()) return null;
        RelevancyResult r = tryJsonRelevancy(text);
        if (r != null) return r;

        Matcher fence = CODE_FENCE.matcher(text);
        if (fence.find()) {
            r = tryJsonRelevancy(fence.group(1));
            if (r != null) return r;
        }
        int lb = text.indexOf('{');
        int rb = text.lastIndexOf('}');
        if (lb >= 0 && rb > lb) {
            r = tryJsonRelevancy(text.substring(lb, rb + 1));
            if (r != null) return r;
        }
        Matcher m = RELEVANT_REGEX.matcher(text);
        if (m.find()) {
            return new RelevancyResult(Boolean.parseBoolean(m.group(1)), "regex-fallback");
        }
        return null;
    }

    private static RelevancyResult tryJsonRelevancy(String s) {
        try {
            JsonNode obj = MAPPER.readTree(s.trim());
            if (!obj.has("relevant") || !obj.get("relevant").isBoolean()) return null;
            boolean relevant = obj.get("relevant").asBoolean();
            String reason = obj.has("reason") ? obj.get("reason").asText("") : "";
            return new RelevancyResult(relevant, reason);
        } catch (Exception ignore) {
            return null;
        }
    }

    // ============================ utility ============================

    public long llmCallCount() { return llmCalls.get(); }
    public long llmFailureCount() { return llmFailures.get(); }

    private void sleepBackoff(int attempt) {
        long sleep = retryBaseSleepMs * (1L << (attempt - 1));
        try {
            Thread.sleep(sleep);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) return 0.0;
        if (v < 0) return 0.0;
        if (v > 1) return 1.0;
        return v;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static String hash(String s) {
        if (s == null) return "0";
        return Integer.toHexString(s.hashCode()) + "_" + s.length();
    }
}
