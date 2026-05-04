package com.hmdp.ai.qweather;

import com.hmdp.ai.config.AiAgentProperties;
import com.hmdp.ai.dto.WeatherDiningAdviceDTO;
import com.hmdp.ai.qweather.dto.AirQualityDTO;
import com.hmdp.ai.qweather.dto.DailyForecastDTO;
import com.hmdp.ai.qweather.dto.LifeIndexDTO;
import com.hmdp.ai.qweather.dto.WeatherNowDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Plan: 天气建议业务编排层。
 *
 * <p>逻辑分两条路径：
 * <ol>
 *   <li><b>线上数据</b>：当 {@code ai.agent.qweather.enabled=true} 且 {@link QWeatherClient} 可用时，
 *       组合 now / forecast / indices / air 多接口结果，输出 source=qweather-real / degraded=false
 *   <li><b>本地降级</b>：QWeather 未启用 / API 失败 / 私钥加载失败时，按月份+场景关键词出本地启发式
 *       建议（搬自原 LocalLifeMcpService.buildWeatherFallback），source=local-fallback / degraded=true
 * </ol>
 *
 * <p>{@code ObjectProvider<QWeatherClient>} 容错：QWeather bean 可能不存在（enabled=false），
 * 也不能让本 service 启动失败——本 service 必须无条件可用，因为 LocalLifeTools 强依赖它。
 */
@Slf4j
@Service
public class WeatherAdvisoryService {

    private final AiAgentProperties properties;
    private final ObjectProvider<QWeatherClient> qweatherClientProvider;

    public WeatherAdvisoryService(AiAgentProperties properties,
                                  ObjectProvider<QWeatherClient> qweatherClientProvider) {
        this.properties = properties;
        this.qweatherClientProvider = qweatherClientProvider;
    }

    /**
     * @param city        城市名（query 来源；可空，会用经纬度回查）
     * @param diningScene 场景关键词，如 "露天聚餐 / 火锅 / 咖啡 / 下午茶"
     * @param lon         经度（可空）
     * @param lat         纬度（可空）
     */
    public WeatherDiningAdviceDTO advise(String city, String diningScene, Double lon, Double lat) {
        if (!properties.getQweather().isEnabled()) {
            return buildLocalFallback(city, diningScene);
        }
        QWeatherClient client = qweatherClientProvider.getIfAvailable();
        if (client == null) {
            log.warn("[qweather] enabled=true but bean missing, fallback to local");
            return buildLocalFallback(city, diningScene);
        }
        try {
            String cityId = resolveCityId(client, city, lon, lat);
            WeatherNowDTO now = client.getCurrentWeather(cityId);
            List<DailyForecastDTO> forecast = client.getDailyForecast(cityId);
            List<LifeIndexDTO> indices = client.getLifeIndices(cityId);
            AirQualityDTO air = tryGetAir(client, cityId);  // 失败吞掉，不强依赖
            return buildAdviceFromRealData(now, forecast, indices, air, diningScene, city);
        } catch (Exception e) {
            log.warn("[qweather] invoke failed, fallback to local. msg={}", e.getMessage());
            return buildLocalFallback(city, diningScene);
        }
    }

    // ===== 真实数据路径 =====

    private String resolveCityId(QWeatherClient client, String city, Double lon, Double lat) {
        if (lon != null && lat != null) {
            return client.lookupCity(lon, lat);
        }
        if (StringUtils.hasText(city)) {
            return client.lookupCityByName(city);
        }
        throw new QWeatherException("Both city and (lon,lat) are blank, cannot resolve cityId");
    }

    private AirQualityDTO tryGetAir(QWeatherClient client, String cityId) {
        try {
            return client.getAirQuality(cityId);
        } catch (Exception e) {
            log.debug("[qweather] air api failed (non-fatal): {}", e.getMessage());
            return null;
        }
    }

    private WeatherDiningAdviceDTO buildAdviceFromRealData(WeatherNowDTO now,
                                                           List<DailyForecastDTO> forecast,
                                                           List<LifeIndexDTO> indices,
                                                           AirQualityDTO air,
                                                           String diningScene,
                                                           String city) {
        WeatherDiningAdviceDTO dto = new WeatherDiningAdviceDTO();
        dto.setCity(StringUtils.hasText(city) ? city : "当前城市");
        dto.setSource("qweather-real");
        dto.setDegraded(false);

        // 1. weatherSummary：自然语言拼接
        StringBuilder summary = new StringBuilder();
        summary.append("当前").append(safe(now.getText())).append("，气温 ").append(safe(now.getTemp()))
                .append("℃，体感 ").append(safe(now.getFeelsLike())).append("℃");
        if (!forecast.isEmpty()) {
            DailyForecastDTO today = forecast.get(0);
            summary.append("，今日 ").append(safe(today.getTempMin())).append("~")
                    .append(safe(today.getTempMax())).append("℃");
        }
        summary.append("，").append(safe(now.getWindDir())).append(" ").append(safe(now.getWindScale())).append("级");
        if (air != null && StringUtils.hasText(air.getCategory())) {
            summary.append("，空气质量").append(air.getCategory());
            if (StringUtils.hasText(air.getAqi())) {
                summary.append("(AQI ").append(air.getAqi()).append(")");
            }
        }
        summary.append("。");
        dto.setWeatherSummary(summary.toString());

        // 2. diningSuggestion：根据场景关键词 + 实时温度组合
        int temp = parseInt(now.getTemp(), 20);
        String text = safe(now.getText()).toLowerCase(Locale.ROOT);
        String scene = StringUtils.hasText(diningScene) ? diningScene.toLowerCase(Locale.ROOT) : "";
        dto.setDiningSuggestion(buildDiningSuggestion(scene, temp, text));

        // 3. suitableCategories：按 temp 区间挑
        dto.setSuitableCategories(temp <= 18
                ? List.of("火锅", "烧烤", "热汤", "炖菜")
                : List.of("咖啡", "甜品", "茶饮", "轻食"));

        // 4. reminders：附加生活指数
        List<String> reminders = new ArrayList<>();
        for (LifeIndexDTO idx : indices) {
            if (StringUtils.hasText(idx.getCategory()) && StringUtils.hasText(idx.getName())) {
                reminders.add(idx.getName() + "：" + idx.getCategory());
            }
        }
        if (reminders.isEmpty()) {
            reminders.add("出门前再确认门店营业状态");
        }
        dto.setReminders(reminders);
        return dto;
    }

    private String buildDiningSuggestion(String scene, int temp, String weatherText) {
        boolean badWeather = weatherText.contains("雨") || weatherText.contains("雪")
                || weatherText.contains("雾") || weatherText.contains("霾");
        if (scene.contains("露天") || scene.contains("户外")) {
            if (badWeather) return "今天天气不适合露天就餐，建议改为室内门店。";
            if (temp < 10 || temp > 32) return "气温偏" + (temp < 10 ? "冷" : "热") + "，露天体验不佳，建议改室内。";
            return "天气适宜露天就餐，可以尝试带户外座位的门店。";
        }
        if (scene.contains("火锅")) {
            if (temp >= 28) return "气温偏高，火锅建议晚餐或选空调好的门店。";
            if (temp <= 18) return "偏凉天气和火锅很搭，适合聚餐。";
            return "气温适中，火锅可吃，关注门店空调状况。";
        }
        if (scene.contains("咖啡") || scene.contains("下午茶")) {
            return "咖啡 / 下午茶任何天气都适合，可优先窗景或室内安静门店。";
        }
        if (temp <= 18) return "偏凉，更推荐火锅 / 烧烤 / 热汤 / 炖菜等温暖系。";
        return "气温舒适，更推荐咖啡 / 甜品 / 茶饮 / 轻食类。";
    }

    private static int parseInt(String s, int defaultVal) {
        if (!StringUtils.hasText(s)) return defaultVal;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    // ===== 本地降级路径（搬自原 LocalLifeMcpService.buildWeatherFallback）=====

    private WeatherDiningAdviceDTO buildLocalFallback(String city, String diningScene) {
        WeatherDiningAdviceDTO dto = new WeatherDiningAdviceDTO();
        dto.setCity(StringUtils.hasText(city) ? city : "当前城市");
        dto.setSource("local-fallback");
        dto.setDegraded(true);

        int month = LocalDate.now().getMonthValue();
        boolean warmSeason = month >= 5 && month <= 9;
        String normalizedScene = StringUtils.hasText(diningScene) ? diningScene.toLowerCase(Locale.ROOT) : "";
        dto.setWeatherSummary(warmSeason
                ? "当前按季节估算偏暖，外出用餐更适合轻食、咖啡、甜品或夜宵场景。"
                : "当前按季节估算偏凉，外出用餐更适合火锅、烧烤、热汤类场景。");

        if (normalizedScene.contains("露天") || normalizedScene.contains("户外")) {
            dto.setDiningSuggestion(warmSeason
                    ? "如果风不大，露天座位通常体验不错，但建议避开正午暴晒时段。"
                    : "户外体感可能偏冷，优先选择室内座位或带加热设施的门店。");
        } else if (normalizedScene.contains("火锅")) {
            dto.setDiningSuggestion(warmSeason
                    ? "如果今天偏热，火锅更适合晚餐或空调较好的门店。"
                    : "偏凉天气和火锅更匹配，适合聚餐。");
        } else {
            dto.setDiningSuggestion(warmSeason
                    ? "更推荐咖啡、甜品、茶饮或通风好的轻餐。"
                    : "更推荐火锅、炖菜、热饮或室内舒适型门店。");
        }

        dto.setSuitableCategories(warmSeason
                ? List.of("咖啡", "甜品", "茶饮", "轻食")
                : List.of("火锅", "烧烤", "热汤", "炖菜"));
        dto.setReminders(warmSeason
                ? List.of("注意补水", "露天就餐尽量避开正午", "夜间出行可优先选步行街或商场店")
                : List.of("优先选室内门店", "出门前确认排队情况", "夜间就餐注意保暖"));
        return dto;
    }
}
