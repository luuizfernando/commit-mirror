package com.luiz.commit_mirror.service;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.luiz.commit_mirror.dto.TelegramMessage;
import com.luiz.commit_mirror.dto.TelegramUpdate;
import com.luiz.commit_mirror.integration.TelegramClient;
import com.luiz.commit_mirror.service.command.BotCommand;
import com.luiz.commit_mirror.service.command.CommandContext;
import com.luiz.commit_mirror.service.command.CommandRegistry;

@Service
public class UpdateDispatcher {

	private static final Logger LOGGER = LoggerFactory.getLogger(UpdateDispatcher.class);

	private final UserAllowlist allowlist;

	private final CommandParser parser;

	private final CommandRegistry registry;

	private final TelegramClient telegramClient;

	public UpdateDispatcher(
		UserAllowlist allowlist,
		CommandParser parser,
		CommandRegistry registry,
		TelegramClient telegramClient
	) {
		this.allowlist = allowlist;
		this.parser = parser;
		this.registry = registry;
		this.telegramClient = telegramClient;
	}

	public void dispatch(TelegramUpdate update) {
		TelegramMessage message = update.message();
		if (message == null || message.text() == null || message.from() == null || message.chat() == null) {
			return;
		}
		if (!allowlist.isAllowed(message.from().id())) {
			LOGGER.warn("Ignoring update from a user outside the allowlist, updateId={}", update.updateId());
			return;
		}
		Optional<ParsedCommand> parsed = parser.parse(message.text());
		if (parsed.isEmpty()) {
			return;
		}
		ParsedCommand command = parsed.get();
		Optional<BotCommand> handler = registry.find(command.name());
		if (handler.isEmpty()) {
			LOGGER.warn("Unknown command received, updateId={}, command={}", update.updateId(), command.name());
			telegramClient.sendMessage(message.chat().id(), "Comando desconhecido: /" + command.name());
			return;
		}
		handler.get()
				.execute(new CommandContext(update.updateId(), message.chat().id(), message.from().id(),
						command.arguments()));
	}

}
