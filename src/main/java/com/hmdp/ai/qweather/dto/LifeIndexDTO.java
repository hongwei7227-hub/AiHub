package com.hmdp.ai.qweather.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * Plan: 和风天气 /v7/indices/1d 生活指数。
 * Plan C 评估时只用 type=1(运动) / 3(穿衣) / 8(舒适度) / 9(感冒)。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class LifeIndexDTO {
    private String date;
    private String type;
    private String name;
    private String level;
    private String category;
    private String text;
}
