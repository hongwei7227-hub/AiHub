package com.hmdp.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ai.agent")
public class AiAgentProperties {

    private Memory memory = new Memory();

    private Rag rag = new Rag();

    private Model model = new Model();

    private Insight insight = new Insight();

    private MultiAgent multiAgent = new MultiAgent();

    private Bootstrap bootstrap = new Bootstrap();

    private Streaming streaming = new Streaming();

    private QWeather qweather = new QWeather();

    @Data
    public static class Memory {
        private int windowSize = 8;
    }

    @Data
    public static class Rag {
        private int topK = 5;
        private double similarityThreshold = 0.65;
        private double knowledgeSimilarityThreshold = 0.35;
        private Collections collections = new Collections();
    }

    @Data
    public static class Model {
        private int connectTimeoutSeconds = 10;
        private int readTimeoutSeconds = 90;
        private int writeTimeoutSeconds = 90;
        private int callTimeoutSeconds = 120;
    }

    @Data
    public static class Insight {
        private int maxReviewSamples = 8;
        private int maxReviewCharsPerSample = 280;
        private int maxCombinedChars = 2200;
        private int maxVectorHits = 4;
        private int maxVectorHitChars = 180;
    }

    @Data
    public static class MultiAgent {
        private int maxSteps = 20;
    }

    @Data
    public static class Bootstrap {
        private boolean enabled = true;
        private boolean failFast = false;
    }

    @Data
    public static class Streaming {
        private boolean enabled = true;
        private int timeoutSeconds = 45;
        private int fallbackChunkSize = 24;
    }

    /**
     * Plan: 和风天气 API 接入配置。
     * 私钥 PEM 路径 + Ed25519 JWT 鉴权所需 (kid, sub)；签发 TTL 短可控（默认 600s 每次签）。
     * 默认 enabled=false，业务启动不要求配置；application-local.yaml 里启用并填真实值。
     */
    @Data
    public static class QWeather {
        private boolean enabled = false;
        private String apiHost;
        private String projectId;
        private String credentialId;
        private String privateKeyPath;
        private int timeoutMillis = 3000;
        private long jwtTtlSeconds = 600;
    }

    @Data
    public static class Collections {
        private String knowledge = "knowledge_vector";
        private String shopProfile = "shop_profile_vector";
        private String blogReview = "blog_review_vector";
    }
}