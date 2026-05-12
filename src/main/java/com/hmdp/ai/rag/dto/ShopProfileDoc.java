package com.hmdp.ai.rag.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * RAG 灌库专用：店铺画像 DTO，对齐 data-prep/output/shop_profile.jsonl 字段。
 *
 * 字段名 1:1 对齐 JSONL（snake_case 通过 @JsonProperty 映射），不做命名转换；
 * shop_id 用 String 兼容 yf_dianping 反推出来的 "restId_45623" 这种字符串 ID。
 */
@Data
public class ShopProfileDoc {

    @JsonProperty("shop_id")
    private String shopId;

    private String name;
    private String city;
    private String category;
    private String address;

    @JsonProperty("avg_price_hint")
    private String avgPriceHint;

    @JsonProperty("signature_dishes")
    private List<String> signatureDishes;

    private Double rating;

    @JsonProperty("rating_flavor")
    private Double ratingFlavor;

    @JsonProperty("rating_env")
    private Double ratingEnv;

    @JsonProperty("rating_service")
    private Double ratingService;

    @JsonProperty("review_count")
    private Integer reviewCount;

    private String content;

    @JsonProperty("enrichment_source")
    private String enrichmentSource;
}
