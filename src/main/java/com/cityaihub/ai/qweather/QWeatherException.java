package com.cityaihub.ai.qweather;

/**
 * Plan: 和风天气 API 调用统一异常。任何环节（JWT 签发、HTTP、JSON parse、code != "200"）
 * 失败后由 client 抛出，上层 {@link WeatherAdvisoryService} 捕获后切到本地降级。
 */
public class QWeatherException extends RuntimeException {

    public QWeatherException(String message) {
        super(message);
    }

    public QWeatherException(String message, Throwable cause) {
        super(message, cause);
    }
}
