package com.cityaihub.ai.rag.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Data;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * RAG 评估专用：评估 query DTO，对齐 eval_queries.jsonl。
 *
 * relevant_ids 在 JSONL 里可能混合 int（knowledge.id）和 String（review_id），
 * 用自定义反序列化器统一转成 List&lt;String&gt;，避免下游做类型分支。
 */
@Data
public class EvalQuery {

    @JsonProperty("query_id")
    private Integer queryId;

    private String query;

    @JsonProperty("target_collection")
    private String targetCollection;

    @JsonProperty("relevant_ids")
    @JsonDeserialize(using = AnyToStringListDeserializer.class)
    private List<String> relevantIds;

    private String notes;

    public static class AnyToStringListDeserializer extends JsonDeserializer<List<String>> {
        @Override
        public List<String> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            List<String> result = new ArrayList<>();
            if (!p.isExpectedStartArrayToken()) {
                return result;
            }
            while (p.nextToken() != null && !p.currentToken().isStructEnd()) {
                result.add(p.getValueAsString());
            }
            return result;
        }
    }
}
