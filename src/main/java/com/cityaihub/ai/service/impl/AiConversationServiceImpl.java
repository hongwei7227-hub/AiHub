package com.cityaihub.ai.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.cityaihub.ai.entity.AiConversation;
import com.cityaihub.ai.mapper.AiConversationMapper;
import com.cityaihub.ai.service.AiConversationService;
import org.springframework.stereotype.Service;

@Service
public class AiConversationServiceImpl extends ServiceImpl<AiConversationMapper, AiConversation> implements AiConversationService {
}
