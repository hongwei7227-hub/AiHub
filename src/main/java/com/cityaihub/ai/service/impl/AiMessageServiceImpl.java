package com.cityaihub.ai.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.cityaihub.ai.entity.AiMessage;
import com.cityaihub.ai.mapper.AiMessageMapper;
import com.cityaihub.ai.service.AiMessageService;
import org.springframework.stereotype.Service;

@Service
public class AiMessageServiceImpl extends ServiceImpl<AiMessageMapper, AiMessage> implements AiMessageService {
}
