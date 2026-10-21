package com.luiz.commit_mirror.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TelegramUpdate(
    @JsonProperty("update_id") long updateId,
    TelegramMessage message
) {}
