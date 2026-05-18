package com.cityaihub.ai.scheduled;

import com.cityaihub.ai.config.AiAgentProperties;
import com.cityaihub.ai.entity.AiConversation;
import com.cityaihub.ai.entity.AiMessage;
import com.cityaihub.ai.service.AiConversationService;
import com.cityaihub.ai.service.AiMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 定时清理 soft-deleted (status=0) 且超过 retention-days 的 conversation 及其 messages。
 *
 * <p><b>软删 vs 硬删的语义边界</b>:
 * <ul>
 *   <li>{@code DbConversationMemoryService.softDeleteConversation}: SET status=0,数据保留,
 *       30 天内可由 DBA 手动 SET status=1 还原</li>
 *   <li>本 task: 30 天后物理 DELETE FROM tb_ai_conversation + tb_ai_message,**不可逆**</li>
 * </ul>
 *
 * <p>用户视角"彻底删除不可逆"的体验由两层保证:
 * <ol>
 *   <li>UI 删除 → 调 softDeleteConversation → 列表立刻消失(用户感受"删了")</li>
 *   <li>30 天后本 task 真删 → 任何还原途径都没了</li>
 * </ol>
 *
 * <p>cron 默认每天凌晨 3 点跑(避开业务高峰),通过 {@code ai.agent.conversation.cleanup-cron} 配置。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationCleanupTask {

    private final AiConversationService conversationService;

    private final AiMessageService messageService;

    private final AiAgentProperties aiAgentProperties;

    @Scheduled(cron = "${ai.agent.conversation.cleanup-cron:0 0 3 * * ?}")
    @Transactional
    public void cleanup() {
        int retentionDays = aiAgentProperties.getConversation().getRetentionDays();
        LocalDateTime threshold = LocalDateTime.now().minusDays(retentionDays);

        List<AiConversation> expired = conversationService.lambdaQuery()
                .eq(AiConversation::getStatus, 0)
                .lt(AiConversation::getUpdateTime, threshold)
                .list();

        if (expired == null || expired.isEmpty()) {
            log.info("[cleanup] no soft-deleted conversation to purge (threshold={}, retention={}d)",
                    threshold, retentionDays);
            return;
        }

        List<Long> ids = expired.stream().map(AiConversation::getId).toList();

        // 先删 messages (foreign key constraint 安全), 再删 conversations
        long messagesCount = messageService.lambdaQuery()
                .in(AiMessage::getConversationId, ids)
                .count();
        boolean msgsRemoved = messageService.lambdaUpdate()
                .in(AiMessage::getConversationId, ids)
                .remove();
        boolean convsRemoved = conversationService.removeByIds(ids);

        log.info("[cleanup] purged {} conversation(s) + {} message(s), threshold={}, retention={}d, "
                        + "msgs_ok={}, convs_ok={}",
                ids.size(), messagesCount, threshold, retentionDays, msgsRemoved, convsRemoved);
    }
}
