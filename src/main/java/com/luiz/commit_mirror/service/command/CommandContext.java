package com.luiz.commit_mirror.service.command;

public record CommandContext(
    Long updateId,
    Long chatId,
    Long userId,
    String arguments
) {
}
