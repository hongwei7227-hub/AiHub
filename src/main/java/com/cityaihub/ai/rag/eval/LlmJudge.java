package com.cityaihub.ai.rag.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plan C：单条 (query, retrievedDoc) → boolean 二元相关性裁判。
 *
 * <p>独立 OpenAiChatModel 指向本地反代（Claude Haiku 4.5），<b>不暴露成 Spring bean</b>——
 * 内部 lazy init，避免与业务 autoconfigured ChatModel 在 by-type 装配上冲突
 * （业务 {@code ManualToolAgentLoopExecutor} 用无 Qualifier 的 {@code @Autowired ChatModel}，
 * 一旦多个 ChatModel bean 就 fail）。
 *
 * <p>关键设计：
 * <ul>
 *   <li>缓存粒度：(query, collection, doc.id) → bool。collection 维度是防御性设计
 *   <li>失败兜底：达到 max-retries 仍 parse 不出 → 默认 false（保守判断）+ log warn
 *   <li>lenient parse：先 strip ` ```json...``` ` markdown，再尝试 JSON parse，仍失败则正则抓
 *       {@code "relevant":\s*(true|false)}
 * </ul>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "rag.eval.llm-judge.enabled", havingValue = "true")
public class LlmJudge {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern CODE_FENCE = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
    private static final Pattern RELEVANT_REGEX = Pattern.compile("\"relevant\"\\s*:\\s*(true|false)", Pattern.CASE_INSENSITIVE);

    private ChatModel judgeChatModel;   // lazy init in @PostConstruct
    private final Map<String, JudgeResult> cache = new ConcurrentHashMap<>();
    private final AtomicLong llmCalls = new AtomicLong();
    private final AtomicLong llmFailures = new AtomicLong();

    /** 单条 (query, doc) 判断结果。reason 保留下来供 audit / 报告。 */
    public static final class JudgeResult {
        public final boolean relevant;
        public final String reason;
        public JudgeResult(boolean relevant, String reason) {
            this.relevant = relevant;
            this.reason = reason == null ? "" : reason;
        }
        public static JudgeResult of(boolean relevant) { return new JudgeResult(relevant, ""); }
    }

    @Value("${rag.eval.llm-judge.base-url}")
    private String baseUrl;

    @Value("${rag.eval.llm-judge.api-key}")
    private String apiKey;

    @Value("${rag.eval.llm-judge.model}")
    private String judgeModel;

    @Value("${rag.eval.llm-judge.max-retries:5}")
    private int maxRetries;

    @Value("${rag.eval.llm-judge.retry-base-sleep-ms:3000}")
    private long retryBaseSleepMs;

    @Value("${rag.eval.llm-judge.doc-content-truncate:300}")
    private int docContentTruncate;

    @Value("${rag.eval.llm-judge.cache-enabled:true}")
    private boolean cacheEnabled;

    @PostConstruct
    void init() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "rag.eval.llm-judge.api-key is blank. Set env LLM_JUDGE_API_KEY before running eval tests.");
        }
        log.info("[llm-judge] init judgeChatModel base-url={} model={} (apiKey hidden)", baseUrl, judgeModel);

        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();
        // maxTokens=1200：单 query 批判最多 top-10 doc，输出 ~10 个 {"index":N,"relevant":bool,"reason":"<10字>"} ≈ 500 tokens，1200 留足余量
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(judgeModel)
                .temperature(0.0)
                .maxTokens(1200)
                .build();
        // 关掉 Spring AI 内部 RetryTemplate（默认 5 次 + 指数退避会让单次 503 hang 5+ 分钟）。
        // 我们自己在 doJudgeWithRetry 里有更短的重试 + tighter backoff。
        RetryTemplate noRetry = RetryTemplate.builder().maxAttempts(1).build();
        this.judgeChatModel = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .retryTemplate(noRetry)
                .build();
    }

    /**
     * 判断 retrievedDoc 是否与 query 相关。
     *
     * @param query      用户查询
     * @param collection 评估的 collection 名（cache key 防撞维度）
     * @param doc        retrieved 文档
     * @return true=相关 / false=不相关或 LLM 调用全部失败兜底
     */
    public boolean judge(String query, String collection, Document doc) {
        return judgeWithResult(query, collection, doc).relevant;
    }

    /** 同 judge() 但返回 JudgeResult（带 reason），供详情导出用。 */
    public JudgeResult judgeWithResult(String query, String collection, Document doc) {
        if (!cacheEnabled) {
            return doJudgeWithRetry(query, doc);
        }
        String cacheKey = query + "||" + collection + "||" + doc.getId();
        return cache.computeIfAbsent(cacheKey, k -> doJudgeWithRetry(query, doc));
    }

    private JudgeResult doJudgeWithRetry(String query, Document doc) {
        String prompt = buildPrompt(query, doc);
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                llmCalls.incrementAndGet();
                ChatResponse response = judgeChatModel.call(new Prompt(new UserMessage(prompt)));
                String text = response.getResult().getOutput().getText();
                JudgeResult parsed = parseRelevant(text);
                if (parsed != null) {
                    return parsed;
                }
                log.warn("[llm-judge] attempt {}/{} parse failed, raw='{}'",
                        attempt, maxRetries, truncate(text, 200));
            } catch (Exception e) {
                log.warn("[llm-judge] attempt {}/{} call failed: {}", attempt, maxRetries, e.toString());
            }
            if (attempt < maxRetries) {
                sleepBackoff(attempt);
            }
        }
        llmFailures.incrementAndGet();
        log.warn("[llm-judge] all {} attempts failed for query='{}', doc.id={} → defaulting to false",
                maxRetries, truncate(query, 80), doc.getId());
        return new JudgeResult(false, "ALL_RETRIES_FAILED");
    }

    /**
     * Plan C 优化：单 query 批判 top-K doc，一次 LLM 调用判 N 个文档相关性。
     *
     * <p>配额优化：把"500 次单调用"压缩为"50 次批调用"（top-10 配置下），
     * 因为反代按次计费，节省 10x。
     *
     * <p>缓存策略：仍按 (query, collection, doc.id) 单条粒度存，重跑能复用。
     * 调用前先检查缓存，全命中直接返回；否则发批 LLM 调用，返回后把每条结果回填缓存。
     *
     * @return Map&lt;docId, bool&gt;，包含全部 docs 的判断
     */
    public Map<String, Boolean> judgeBatch(String query, String collection, List<Document> docs) {
        Map<String, JudgeResult> rich = judgeBatchWithResult(query, collection, docs);
        Map<String, Boolean> bools = new HashMap<>(rich.size());
        rich.forEach((k, v) -> bools.put(k, v.relevant));
        return bools;
    }

    /** 同 judgeBatch() 但返回 JudgeResult（带 reason），供详情导出用。 */
    public Map<String, JudgeResult> judgeBatchWithResult(String query, String collection, List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return Map.of();
        }
        Map<String, JudgeResult> result = new HashMap<>(docs.size());
        List<Document> uncached = new ArrayList<>(docs.size());

        // 1) 命中缓存的 doc 直接填 result
        if (cacheEnabled) {
            for (Document d : docs) {
                String key = query + "||" + collection + "||" + d.getId();
                JudgeResult v = cache.get(key);
                if (v != null) {
                    result.put(d.getId(), v);
                } else {
                    uncached.add(d);
                }
            }
        } else {
            uncached.addAll(docs);
        }

        if (uncached.isEmpty()) {
            return result;
        }

        // 2) 对未命中的 doc 发一次 batch LLM 调用
        Map<String, JudgeResult> fresh = doBatchJudgeWithRetry(query, uncached);

        // 3) 回填缓存 + result
        for (Document d : uncached) {
            JudgeResult v = fresh.get(d.getId());
            if (v == null) {
                v = new JudgeResult(false, "MISSING_FROM_BATCH");  // LLM 没返回这个 index → 兜底 false
            }
            result.put(d.getId(), v);
            if (cacheEnabled) {
                cache.put(query + "||" + collection + "||" + d.getId(), v);
            }
        }
        return result;
    }

    private Map<String, JudgeResult> doBatchJudgeWithRetry(String query, List<Document> docs) {
        String prompt = buildBatchPrompt(query, docs);
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                llmCalls.incrementAndGet();
                ChatResponse response = judgeChatModel.call(new Prompt(new UserMessage(prompt)));
                String text = response.getResult().getOutput().getText();
                Map<Integer, JudgeResult> parsed = parseBatchRelevant(text);
                if (parsed != null && !parsed.isEmpty()) {
                    Map<String, JudgeResult> mapped = new HashMap<>(docs.size());
                    for (int i = 0; i < docs.size(); i++) {
                        JudgeResult v = parsed.get(i + 1); // prompt 用 1-based index
                        if (v != null) {
                            mapped.put(docs.get(i).getId(), v);
                        }
                    }
                    if (mapped.size() >= docs.size() / 2) {
                        return mapped;
                    }
                    log.warn("[llm-judge-batch] attempt {}/{} parsed only {}/{} items, retry",
                            attempt, maxRetries, mapped.size(), docs.size());
                } else {
                    log.warn("[llm-judge-batch] attempt {}/{} parse failed, raw='{}'",
                            attempt, maxRetries, truncate(text, 300));
                }
            } catch (Exception e) {
                log.warn("[llm-judge-batch] attempt {}/{} call failed: {}", attempt, maxRetries, e.toString());
            }
            if (attempt < maxRetries) {
                sleepBackoff(attempt);
            }
        }
        llmFailures.incrementAndGet();
        log.warn("[llm-judge-batch] all {} attempts failed for query='{}', {} docs → all defaulting to false",
                maxRetries, truncate(query, 80), docs.size());
        return Map.of();
    }

    private String buildBatchPrompt(String query, List<Document> docs) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("你是 RAG 系统的相关性裁判。对每个\"retrieved 文档\"判断是否能作为\"用户查询\"的相关上下文。\n\n");
        sb.append("不要做搜索，不要说\"我会搜索\"，直接基于给出的信息评分。\n\n");
        sb.append("判断标准：\n");
        sb.append("1. 文档能直接或间接回答用户查询 → 相关\n");
        sb.append("2. 文档主题与查询完全无关 → 不相关\n");
        sb.append("3. 同品类同地域但具体内容不匹配 query 关键意图 → 不相关\n");
        sb.append("4. 模糊情况倾向\"不相关\"\n\n");
        sb.append("输出严格 JSON 数组，必须包含全部 ").append(docs.size()).append(" 个 doc 的判断，按 index 顺序：\n");
        sb.append("[{\"index\":1,\"relevant\":true,\"reason\":\"<10字>\"},{\"index\":2,\"relevant\":false,\"reason\":\"<10字>\"},...]\n\n");
        sb.append("用户查询：").append(query).append("\n\n");
        sb.append("retrieved 文档：\n");
        for (int i = 0; i < docs.size(); i++) {
            String content = docs.get(i).getText() == null ? "" : docs.get(i).getText();
            if (content.length() > docContentTruncate) {
                content = content.substring(0, docContentTruncate) + "...";
            }
            sb.append("[").append(i + 1).append("] ").append(content).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * lenient parse for batch: 解析 JSON array，每项 {"index": N, "relevant": bool}
     * 返回 Map&lt;1-based index, bool&gt;。失败返回 null。
     */
    static Map<Integer, JudgeResult> parseBatchRelevant(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        // 1) 直接尝试 array
        Map<Integer, JudgeResult> v = tryJsonArray(text);
        if (v != null) return v;

        // 2) markdown code fence
        Matcher fence = CODE_FENCE.matcher(text);
        if (fence.find()) {
            v = tryJsonArray(fence.group(1));
            if (v != null) return v;
        }

        // 3) 抓第一个看起来像 array 的子串
        int lb = text.indexOf('[');
        int rb = text.lastIndexOf(']');
        if (lb >= 0 && rb > lb) {
            v = tryJsonArray(text.substring(lb, rb + 1));
            if (v != null) return v;
        }
        return null;
    }

    private static Map<Integer, JudgeResult> tryJsonArray(String s) {
        try {
            JsonNode arr = MAPPER.readTree(s.trim());
            if (!arr.isArray()) return null;
            Map<Integer, JudgeResult> map = new HashMap<>();
            for (JsonNode item : arr) {
                if (item.has("index") && item.has("relevant") && item.get("relevant").isBoolean()) {
                    boolean relevant = item.get("relevant").asBoolean();
                    String reason = item.has("reason") ? item.get("reason").asText("") : "";
                    map.put(item.get("index").asInt(), new JudgeResult(relevant, reason));
                }
            }
            return map.isEmpty() ? null : map;
        } catch (Exception ignore) {
            return null;
        }
    }

    private String buildPrompt(String query, Document doc) {
        String content = doc.getText() == null ? "" : doc.getText();
        if (content.length() > docContentTruncate) {
            content = content.substring(0, docContentTruncate) + "...";
        }
        return "你是 RAG 系统的相关性裁判。判断\"retrieved 文档\"是否能作为\"用户查询\"的相关上下文。\n\n" +
                "不要做搜索，不要说\"我会搜索\"，直接基于给出的信息评分。\n\n" +
                "判断标准：\n" +
                "1. retrieved 文档能直接或间接回答用户查询 → 相关\n" +
                "2. 文档主题与查询完全无关 → 不相关\n" +
                "3. 文档同品类同地域但具体内容不匹配 query 关键意图 → 不相关\n" +
                "4. 模糊情况倾向\"不相关\"\n\n" +
                "输出严格 JSON：{\"relevant\": true/false, \"reason\": \"<10字理由>\"}\n\n" +
                "用户查询：" + query + "\n\n" +
                "retrieved 文档：" + content;
    }

    /**
     * lenient parse：
     * 1) 尝试整体 JSON
     * 2) 抓 markdown code fence 内容再 JSON
     * 3) 正则抓 "relevant": true/false
     * 4) 全部失败返回 null（让上层重试）
     */
    static JudgeResult parseRelevant(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        // 1) 整体 JSON
        JudgeResult v = tryJson(text);
        if (v != null) return v;

        // 2) markdown code fence
        Matcher fence = CODE_FENCE.matcher(text);
        if (fence.find()) {
            v = tryJson(fence.group(1));
            if (v != null) return v;
        }

        // 3) 正则抓 "relevant": true/false（这条路径拿不到 reason）
        Matcher m = RELEVANT_REGEX.matcher(text);
        if (m.find()) {
            return new JudgeResult(Boolean.parseBoolean(m.group(1).toLowerCase()), "");
        }
        return null;
    }

    private static JudgeResult tryJson(String s) {
        try {
            JsonNode node = MAPPER.readTree(s.trim());
            if (node.has("relevant") && node.get("relevant").isBoolean()) {
                String reason = node.has("reason") ? node.get("reason").asText("") : "";
                return new JudgeResult(node.get("relevant").asBoolean(), reason);
            }
        } catch (Exception ignore) {
            // 不是合法 JSON，回退到下一步
        }
        return null;
    }

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(retryBaseSleepMs * (1L << (attempt - 1))); // 3s, 6s, 12s, 24s
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    public int callCount() { return (int) llmCalls.get(); }
    public int failureCount() { return (int) llmFailures.get(); }
    public int cacheSize() { return cache.size(); }
    public Map<String, JudgeResult> snapshotResults() { return Map.copyOf(cache); }
}
