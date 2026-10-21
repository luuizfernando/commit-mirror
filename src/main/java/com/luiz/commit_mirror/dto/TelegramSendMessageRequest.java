package com.luiz.commit_mirror.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TelegramSendMessageRequest(
    @JsonProperty("chat_id") long chatId,
    String text
) {}
