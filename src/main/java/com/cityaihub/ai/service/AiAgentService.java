package com.cityaihub.ai.service;

import com.cityaihub.ai.dto.AgentRecommendRequest;
import com.cityaihub.ai.dto.AgentRecommendResponse;
import com.cityaihub.ai.dto.ConversationSummaryDTO;
import com.cityaihub.ai.dto.IndexRebuildResponse;
import com.cityaihub.ai.dto.MessageDTO;
import com.cityaihub.ai.dto.ShopInsightResponse;
import com.cityaihub.dto.CreateChatMessageRequest;
import com.cityaihub.dto.CreateChatMessageResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

public interface AiAgentService {

    ConversationSummaryDTO createConversation();

    CreateChatMessageResponse createChatMessage(CreateChatMessageRequest request);

    SseEmitter streamConversation(Long conversationId);

    List<ConversationSummaryDTO> listConversations();

    List<MessageDTO> listMessages(Long conversationId);

    AgentRecommendResponse recommend(AgentRecommendRequest request);

    ShopInsightResponse analyzeShop(Long shopId);

    IndexRebuildResponse rebuildAll();

    IndexRebuildResponse rebuildShop(Long shopId);

    void deleteConversation(Long conversationId);
}
