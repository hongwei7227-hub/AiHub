package com.cityaihub.ai.qweather.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * Plan: 和风天气 /v7/air/now 空气质量。
 * 注意：plan 阶段官方文档 404 不可访问，字段按常见 QWeather Air API 响应假设。
 * 上线时若实际响应字段名不一致，DTO 加 ignoreUnknown 容忍，关键字段 (aqi/category) 不影响主流程。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AirQualityDTO {
    private String pubTime;
    private String aqi;
    private String level;
    private String category;
    private String primary;
    private String pm10;
    private String pm2p5;
    private String no2;
    private String so2;
    private String co;
    private String o3;
}
