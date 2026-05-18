package com.hmdp.ai.rag.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * RAG 灌库专用：用户评论 DTO，对齐 blog_review.jsonl。
 */
@Data
public class ReviewDoc {

    @JsonProperty("review_id")
    private String reviewId;

    @JsonProperty("shop_id")
    private String shopId;

    @JsonProperty("user_id")
    private String userId;

    private Double rating;

    @JsonProperty("rating_flavor")
    private Double ratingFlavor;

    @JsonProperty("rating_env")
    private Double ratingEnv;

    @JsonProperty("rating_service")
    private Double ratingService;

    private Long timestamp;

    private String content;
}
