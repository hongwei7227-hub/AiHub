package com.hmdp.ai.memory;

import com.hmdp.ai.dto.ConversationSummaryDTO;
import com.hmdp.ai.dto.MessageDTO;
import com.hmdp.ai.entity.AiConversation;
import com.hmdp.ai.entity.AiMessage;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

public interface ConversationMemoryService {

    AiConversation getOrCreateConversation(Long userId, Long conversationId, String prompt, String scene);

    void updateConversation(Long conversationId, String scene, String latestPrompt);

    AiMessage appendUserMessage(Long conversationId, String content);

    AiMessage appendAssistantMessage(Long conversationId, String content);

    AiMessage appendToolMessage(Long conversationId, String toolName, Object payload);

    List<Message> loadRecentMessages(Long conversationId, int windowSize);

    List<Message> loadRecentMessagesBefore(Long conversationId, Long beforeMessageId, int windowSize);

    void ensureConversationOwnership(Long userId, Long conversationId);

    List<ConversationSummaryDTO> listConversations(Long userId);

    List<MessageDTO> listMessages(Long userId, Long conversationId);

    /** 软删除会话(status=0):验证 owner 后把 conversation 置 inactive。messages 表数据保留。 */
    void softDeleteConversation(Long userId, Long conversationId);
}
