package com.luiz.commit_mirror.service.command;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.luiz.commit_mirror.domain.CommitLog;
import com.luiz.commit_mirror.integration.TelegramClient;
import com.luiz.commit_mirror.service.CommitLogService;
import com.luiz.commit_mirror.service.CommitProcessingService;
import com.luiz.commit_mirror.service.LogWriteSerializer;

@Component
public class CommitCommand implements BotCommand {

	private static final Logger LOGGER = LoggerFactory.getLogger(CommitCommand.class);

	private static final String USAGE = "Uso: /commit <mensagem>";

	private final TelegramClient telegramClient;

	private final CommitLogService commitLogService;

	private final LogWriteSerializer serializer;

	private final CommitProcessingService processing;

	public CommitCommand(TelegramClient telegramClient, CommitLogService commitLogService,
			LogWriteSerializer serializer, CommitProcessingService processing) {
		this.telegramClient = telegramClient;
		this.commitLogService = commitLogService;
		this.serializer = serializer;
		this.processing = processing;
	}

	@Override
	public String name() {
		return "commit";
	}

	@Override
	public void execute(CommandContext context) {
		if (context.arguments().isBlank()) {
			LOGGER.warn("Commit command received without a message, updateId={}", context.updateId());
			telegramClient.sendMessage(context.chatId(), USAGE);
			return;
		}
		Optional<CommitLog> registered = commitLogService.register(context.updateId(), context.chatId(),
				context.arguments());
		if (registered.isEmpty()) {
			LOGGER.warn("Ignoring duplicate update, updateId={}", context.updateId());
			return;
		}
		CommitLog log = registered.get();
		LOGGER.info("Commit registered as pending, updateId={}, sequenceNumber={}", context.updateId(),
				log.getSequenceNumber());
		long id = log.getId();
		serializer.submit(() -> processing.process(id));
	}

}
