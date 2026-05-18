package com.cityaihub.ai.qweather.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * Plan: 和风天气 /v7/weather/now 实时天气响应（now 子对象字段）。
 * 字段对齐官方文档；用 ignoreUnknown 容忍 API 加新字段。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class WeatherNowDTO {
    private String obsTime;
    private String temp;
    private String feelsLike;
    private String icon;
    private String text;
    private String wind360;
    private String windDir;
    private String windScale;
    private String windSpeed;
    private String humidity;
    private String precip;
    private String pressure;
    private String vis;
    private String cloud;
    private String dew;
}
