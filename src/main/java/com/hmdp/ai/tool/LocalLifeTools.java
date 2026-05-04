package com.hmdp.ai.tool;

import com.hmdp.ai.dto.RouteAdviceDTO;
import com.hmdp.ai.dto.WeatherDiningAdviceDTO;
import com.hmdp.ai.locallife.LocalLifeService;
import com.hmdp.ai.qweather.WeatherAdvisoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Plan: Agent 本地生活工具集（路线 + 天气）。
 *
 * <p>取代原 {@code McpLocalLifeTools}：
 * <ul>
 *   <li>路线工具委托 {@link LocalLifeService}，本地 Haversine 公式估算
 *   <li>天气工具委托 {@link WeatherAdvisoryService}，接和风天气 JWT API（Ed25519 鉴权），
 *       失败自动降级到本地季节性建议
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class LocalLifeTools {

    private final LocalLifeService localLifeService;
    private final WeatherAdvisoryService weatherAdvisoryService;

    @Tool(description = "通过 Haversine 公式估算用户到目标店铺的距离，给出步行/骑行/打车建议。")
    public RouteAdviceDTO getRouteAdvice(
            @ToolParam(description = "目标店铺名称") String destinationName,
            @ToolParam(description = "目标店铺地址") String destinationAddress,
            @ToolParam(description = "用户经度") Double userX,
            @ToolParam(description = "用户纬度") Double userY,
            @ToolParam(description = "店铺经度") Double shopX,
            @ToolParam(description = "店铺纬度") Double shopY) {
        return localLifeService.routeAdvice(destinationName, destinationAddress, userX, userY, shopX, shopY);
    }

    @Tool(description = "通过和风天气 API 获取当前城市实时天气、生活指数和空气质量，结合用餐场景给出建议。如果外部 API 不可用，会自动降级为基于季节的本地估算。")
    public WeatherDiningAdviceDTO getWeatherDiningAdvice(
            @ToolParam(description = "城市名称，例如北京") String city,
            @ToolParam(description = "用餐场景，例如露天、火锅、咖啡、约会、聚餐") String diningScene) {
        // 当前 @Tool 不暴露经纬度参数（给 LLM 加无意义字段）；如需精确定位，由调用方在 prompt 上下文里给出
        return weatherAdvisoryService.advise(city, diningScene, null, null);
    }
}
