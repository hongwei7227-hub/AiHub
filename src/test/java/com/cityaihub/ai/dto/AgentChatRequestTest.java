package com.cityaihub.ai.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AgentChatRequestTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldDeserializePromptField() throws Exception {
        AgentChatRequest request = objectMapper.readValue("""
                {
                  "conversationId": 1,
                  "prompt": "你好"
                }
                """, AgentChatRequest.class);

        assertEquals(1L, request.getConversationId());
        assertEquals("你好", request.getPrompt());
    }

    @Test
    void shouldDeserializeLegacyContentField() throws Exception {
        AgentChatRequest request = objectMapper.readValue("""
                {
                  "conversationId": 1,
                  "content": "你好"
                }
                """, AgentChatRequest.class);

        assertEquals(1L, request.getConversationId());
        assertEquals("你好", request.getPrompt());
    }

    @Test
    void shouldDeserializeShopIdWhenPresent() throws Exception {
        AgentChatRequest request = objectMapper.readValue("""
                {
                  "conversationId": 1,
                  "prompt": "这家好不好吃",
                  "shopId": 12345
                }
                """, AgentChatRequest.class);

        assertEquals(12345L, request.getShopId());
    }

    @Test
    void shouldAllowMissingShopId() throws Exception {
        AgentChatRequest request = objectMapper.readValue("""
                {
                  "conversationId": 1,
                  "prompt": "附近有什么好吃的"
                }
                """, AgentChatRequest.class);

        assertNull(request.getShopId());
    }
}