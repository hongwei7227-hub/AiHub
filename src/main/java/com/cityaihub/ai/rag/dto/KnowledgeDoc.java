package com.cityaihub.ai.rag.dto;

import lombok.Data;

/**
 * RAG 灌库专用：美食知识 QA DTO，对齐 knowledge_qa.jsonl。
 */
@Data
public class KnowledgeDoc {

    private Integer id;
    private String topic;
    private String question;
    private String answer;
    private String content;
}
