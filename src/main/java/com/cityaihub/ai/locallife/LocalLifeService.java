package com.hmdp.ai.locallife;

import com.hmdp.ai.dto.RouteAdviceDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Plan: 本地生活路线建议（Haversine 公式估算）。
 *
 * <p>从原 {@code com.hmdp.ai.mcp.LocalLifeMcpService} 拆出路线部分，搬到 {@code com.hmdp.ai.locallife} 包。
 * 天气能力已分离到 {@link com.hmdp.ai.qweather.WeatherAdvisoryService}（接和风 API）。
 *
 * <p>为什么路线保留本地：Haversine 计算坐标距离已足够回答"多远 / 步行 or 打车"，
 * 接外部地图 API 的边际收益（实时路况）不匹配本地生活推荐场景的精度需求。
 */
@Slf4j
@Service
public class LocalLifeService {

    public RouteAdviceDTO routeAdvice(String destinationName,
                                      String destinationAddress,
                                      Double userX,
                                      Double userY,
                                      Double shopX,
                                      Double shopY) {
        RouteAdviceDTO dto = new RouteAdviceDTO();
        dto.setDestinationName(StringUtils.hasText(destinationName) ? destinationName : "目标店铺");
        dto.setSource("local-haversine");
        dto.setDegraded(false);

        Integer distanceMeters = estimateDistanceMeters(userX, userY, shopX, shopY);
        dto.setDistanceMeters(distanceMeters);
        if (distanceMeters == null) {
            dto.setTravelMode("到店建议");
            dto.setDurationSuggestion("请结合实时地图查看");
            dto.setRouteSummary("暂时无法获取精确路线，建议直接打开地图 App 搜索“" + dto.getDestinationName() + "”。");
            dto.getSuggestions().add("优先核对门店地址：" + (StringUtils.hasText(destinationAddress) ? destinationAddress : "请在详情页查看地址"));
            dto.getSuggestions().add("如果赶时间，优先打车；如果附近停车不便，优先地铁或骑行。");
            return dto;
        }

        dto.setTravelMode(distanceMeters <= 1800 ? "步行 / 骑行优先"
                : distanceMeters <= 6000 ? "地铁 / 骑行优先" : "打车 / 地铁优先");
        dto.setDurationSuggestion(buildDurationSuggestion(distanceMeters));
        dto.setRouteSummary("基于经纬度估算，距“" + dto.getDestinationName() + "”约 " + distanceMeters
                + " 米，可按 " + dto.getTravelMode() + " 规划行程。");
        dto.getSuggestions().add(distanceMeters <= 1800
                ? "距离较近，步行或骑行通常最省时。"
                : "建议出发前先在地图确认实时拥堵和停车情况。");
        dto.getSuggestions().add("如果是饭点前往，尽量预留 10-20 分钟排队或找车位时间。");
        return dto;
    }

    /**
     * Haversine 公式：球面两点距离（米）。
     * 任一坐标为 null 返回 null（上层走"地图 App"兜底分支）。
     */
    private Integer estimateDistanceMeters(Double userX, Double userY, Double shopX, Double shopY) {
        if (userX == null || userY == null || shopX == null || shopY == null) {
            return null;
        }
        double earthRadius = 6371000.0;
        double lat1 = Math.toRadians(userY);
        double lat2 = Math.toRadians(shopY);
        double deltaLat = Math.toRadians(shopY - userY);
        double deltaLon = Math.toRadians(shopX - userX);

        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return (int) Math.round(earthRadius * c);
    }

    private String buildDurationSuggestion(int distanceMeters) {
        int walkMinutes = Math.max(3, (int) Math.ceil(distanceMeters / 80.0));
        int rideMinutes = Math.max(5, (int) Math.ceil(distanceMeters / 250.0));
        int taxiMinutes = Math.max(8, (int) Math.ceil(distanceMeters / 450.0));
        return "步行约 " + walkMinutes + " 分钟，骑行约 " + rideMinutes + " 分钟，打车约 " + taxiMinutes + " 分钟。";
    }
}
