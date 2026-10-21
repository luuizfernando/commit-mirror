package com.luiz.commit_mirror.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TelegramMessage(
	@JsonProperty("message_id") long messageId,
	TelegramUser from,
	TelegramChat chat,
	String text
) {}
