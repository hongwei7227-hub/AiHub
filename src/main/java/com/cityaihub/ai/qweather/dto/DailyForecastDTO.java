package com.hmdp.ai.qweather.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * Plan: 和风天气 /v7/weather/3d 每日预报（daily 数组的单项）。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DailyForecastDTO {
    private String fxDate;
    private String sunrise;
    private String sunset;
    private String tempMax;
    private String tempMin;
    private String iconDay;
    private String textDay;
    private String iconNight;
    private String textNight;
    private String windDirDay;
    private String windScaleDay;
    private String windSpeedDay;
    private String windDirNight;
    private String windScaleNight;
    private String windSpeedNight;
    private String humidity;
    private String precip;
    private String pressure;
    private String vis;
    private String uvIndex;
}
