package com.cityaihub.dto;

import lombok.Data;

@Data
public class CreateChatMessageRequest {

    private Long conversationId;

    private String content;

    private Double x;

    private Double y;

    private Long shopId;
}
