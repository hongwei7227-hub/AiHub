package com.hmdp.ai.qweather;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.config.AiAgentProperties;
import com.hmdp.ai.qweather.dto.AirQualityDTO;
import com.hmdp.ai.qweather.dto.DailyForecastDTO;
import com.hmdp.ai.qweather.dto.LifeIndexDTO;
import com.hmdp.ai.qweather.dto.WeatherNowDTO;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.Ed25519Signer;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Plan: 和风天气 API 客户端。
 *
 * <p>关键设计：
 * <ul>
 *   <li>启动时 ({@link PostConstruct}) 加载 PEM 私钥到 {@link OctetKeyPair}（Ed25519）
 *   <li>JWT 缓存 + 自动续期：剩余 TTL &lt; 60s 重新签发
 *   <li>HTTP 用 JDK {@link HttpClient}，带 {@code Accept-Encoding: gzip}；
 *       响应解压看 {@code Content-Encoding} 实际值，没有就当裸 JSON（plan 修正点 2）
 *   <li>所有 endpoint（含 GeoAPI）都走用户私有 {@code apiHost}（plan 修正点 1，
 *       公开域名 api.qweather.com 2026 起停用）
 *   <li>任一失败统一抛 {@link QWeatherException}，由 {@link WeatherAdvisoryService} 降级
 * </ul>
 *
 * <p>仅当 {@code ai.agent.qweather.enabled=true} 才注册 bean，业务启动不强求私钥配置。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ai.agent.qweather", name = "enabled", havingValue = "true")
public class QWeatherClient {

    private final AiAgentProperties properties;
    private final ObjectMapper objectMapper;

    private HttpClient httpClient;
    private OctetKeyPair privateKey;

    private volatile String cachedJwt;
    private volatile long jwtExpiresAtSeconds;

    @PostConstruct
    void init() {
        AiAgentProperties.QWeather cfg = properties.getQweather();
        validateConfig(cfg);
        this.privateKey = loadPrivateKey(cfg.getPrivateKeyPath());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(cfg.getTimeoutMillis()))
                .build();
        log.info("[qweather] client initialized: apiHost={}, projectId={}, kid={}, privateKey={}",
                cfg.getApiHost(), cfg.getProjectId(), cfg.getCredentialId(),
                cfg.getPrivateKeyPath());
    }

    // ===== 公共 API =====

    /**
     * 经纬度反查 cityId（GeoAPI）。返回 location[0].id，找不到抛异常。
     * 注意：plan 修正点 1，GeoAPI 也走用户私有 apiHost，不是 geoapi.qweather.com。
     */
    public String lookupCity(double lon, double lat) {
        String location = lon + "," + lat;
        return doLookupCity(location);
    }

    /** 城市名查 cityId，模糊匹配 */
    public String lookupCityByName(String city) {
        if (!StringUtils.hasText(city)) {
            throw new QWeatherException("city is blank for GeoAPI lookup");
        }
        return doLookupCity(URLEncoder.encode(city, StandardCharsets.UTF_8));
    }

    public WeatherNowDTO getCurrentWeather(String cityId) {
        JsonNode root = invoke("/v7/weather/now?location=" + cityId);
        return objectMapper.convertValue(root.path("now"), WeatherNowDTO.class);
    }

    public List<DailyForecastDTO> getDailyForecast(String cityId) {
        JsonNode root = invoke("/v7/weather/3d?location=" + cityId);
        return objectMapper.convertValue(root.path("daily"),
                new TypeReference<List<DailyForecastDTO>>() {});
    }

    /** 生活指数：type=1(运动) 3(穿衣) 8(舒适度) 9(感冒)，逗号拼接 */
    public List<LifeIndexDTO> getLifeIndices(String cityId) {
        JsonNode root = invoke("/v7/indices/1d?type=1,3,8,9&location=" + cityId);
        return objectMapper.convertValue(root.path("daily"),
                new TypeReference<List<LifeIndexDTO>>() {});
    }

    public AirQualityDTO getAirQuality(String cityId) {
        JsonNode root = invoke("/v7/air/now?location=" + cityId);
        return objectMapper.convertValue(root.path("now"), AirQualityDTO.class);
    }

    // ===== 内部：HTTP / JSON =====

    private JsonNode invoke(String pathAndQuery) {
        AiAgentProperties.QWeather cfg = properties.getQweather();
        String url = "https://" + cfg.getApiHost() + pathAndQuery;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(cfg.getTimeoutMillis()))
                    .header("Authorization", "Bearer " + getOrRefreshJwt())
                    .header("Accept-Encoding", "gzip")
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = httpClient.send(req, BodyHandlers.ofByteArray());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new QWeatherException("HTTP " + resp.statusCode() + " for " + pathAndQuery);
            }
            String body = decodeBody(resp);
            JsonNode root = objectMapper.readTree(body);
            String code = root.path("code").asText("");
            if (!"200".equals(code)) {
                throw new QWeatherException("QWeather code=" + code + " for " + pathAndQuery
                        + " (body=" + truncate(body) + ")");
            }
            return root;
        } catch (QWeatherException qe) {
            throw qe;
        } catch (Exception e) {
            throw new QWeatherException("QWeather call failed: " + pathAndQuery, e);
        }
    }

    private String doLookupCity(String location) {
        AiAgentProperties.QWeather cfg = properties.getQweather();
        String url = "https://" + cfg.getApiHost() + "/geo/v2/city/lookup?location=" + location;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(cfg.getTimeoutMillis()))
                    .header("Authorization", "Bearer " + getOrRefreshJwt())
                    .header("Accept-Encoding", "gzip")
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = httpClient.send(req, BodyHandlers.ofByteArray());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new QWeatherException("GeoAPI HTTP " + resp.statusCode());
            }
            String body = decodeBody(resp);
            JsonNode root = objectMapper.readTree(body);
            String code = root.path("code").asText("");
            if (!"200".equals(code)) {
                throw new QWeatherException("GeoAPI code=" + code);
            }
            JsonNode loc = root.path("location");
            if (!loc.isArray() || loc.size() == 0) {
                throw new QWeatherException("GeoAPI returned empty location list");
            }
            String id = loc.get(0).path("id").asText("");
            if (!StringUtils.hasText(id)) {
                throw new QWeatherException("GeoAPI returned empty cityId");
            }
            return id;
        } catch (QWeatherException qe) {
            throw qe;
        } catch (Exception e) {
            throw new QWeatherException("GeoAPI lookup failed", e);
        }
    }

    /**
     * Plan 修正点 2：gzip header 不一定带，看 {@code Content-Encoding} 实际值决定是否解压。
     */
    private String decodeBody(HttpResponse<byte[]> resp) throws IOException {
        byte[] raw = resp.body();
        boolean gzip = resp.headers().firstValue("Content-Encoding")
                .map(v -> v.toLowerCase().contains("gzip"))
                .orElse(false);
        if (gzip) {
            try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(raw))) {
                return new String(gz.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return new String(raw, StandardCharsets.UTF_8);
    }

    // ===== 内部：JWT =====

    private String getOrRefreshJwt() {
        long now = System.currentTimeMillis() / 1000;
        if (cachedJwt != null && jwtExpiresAtSeconds - now > 60) {
            return cachedJwt;
        }
        synchronized (this) {
            if (cachedJwt != null && jwtExpiresAtSeconds - now > 60) {
                return cachedJwt;
            }
            cachedJwt = signJwt();
            jwtExpiresAtSeconds = now + properties.getQweather().getJwtTtlSeconds();
            return cachedJwt;
        }
    }

    private String signJwt() {
        AiAgentProperties.QWeather cfg = properties.getQweather();
        long now = System.currentTimeMillis() / 1000;
        try {
            JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.EdDSA)
                    .keyID(cfg.getCredentialId())
                    .type(JOSEObjectType.JWT)
                    .build();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(cfg.getProjectId())
                    .issueTime(new Date((now - 30) * 1000))   // 提前 30s 容时钟偏差
                    .expirationTime(new Date((now + cfg.getJwtTtlSeconds()) * 1000))
                    .build();
            SignedJWT jwt = new SignedJWT(header, claims);
            jwt.sign(new Ed25519Signer(privateKey));
            return jwt.serialize();
        } catch (Exception e) {
            throw new QWeatherException("Failed to sign JWT", e);
        }
    }

    // ===== 启动期辅助 =====

    private static void validateConfig(AiAgentProperties.QWeather cfg) {
        List<String> missing = new ArrayList<>();
        if (!StringUtils.hasText(cfg.getApiHost())) missing.add("api-host");
        if (!StringUtils.hasText(cfg.getProjectId())) missing.add("project-id");
        if (!StringUtils.hasText(cfg.getCredentialId())) missing.add("credential-id");
        if (!StringUtils.hasText(cfg.getPrivateKeyPath())) missing.add("private-key-path");
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "ai.agent.qweather.enabled=true but missing config: " + missing
                            + ". Set them via env or application-local.yaml");
        }
    }

    private static OctetKeyPair loadPrivateKey(String path) {
        try {
            String pem = Files.readString(Path.of(path), StandardCharsets.UTF_8);
            return OctetKeyPair.parseFromPEMEncodedObjects(pem).toOctetKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load QWeather private key from " + path, e);
        }
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }
}
