package com.luiz.commit_mirror.service.command;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.luiz.commit_mirror.integration.TelegramClient;
import com.luiz.commit_mirror.service.CommitLogService;

@Component
public class StatusCommand implements BotCommand {

	private static final Logger LOGGER = LoggerFactory.getLogger(StatusCommand.class);

	private static final String NO_COMMITS_REPLY = "Nenhum commit salvo ainda.";

	private static final String SINGLE_COMMIT_REPLY = "Você já salvou 1 commit com o Commit Mirror.";

	private static final String MANY_COMMITS_REPLY = "Você já salvou %d commits com o Commit Mirror.";

	private final TelegramClient telegramClient;

	private final CommitLogService commitLogService;

	public StatusCommand(TelegramClient telegramClient, CommitLogService commitLogService) {
		this.telegramClient = telegramClient;
		this.commitLogService = commitLogService;
	}

	@Override
	public String name() {
		return "status";
	}

	@Override
	public void execute(CommandContext context) {
		long mirroredCommits = commitLogService.countMirroredCommits();
		LOGGER.info("Status requested, updateId={}, mirroredCommits={}", context.updateId(), mirroredCommits);
		telegramClient.sendMessage(context.chatId(), reply(mirroredCommits));
	}

	private static String reply(long mirroredCommits) {
		if (mirroredCommits == 0) {
			return NO_COMMITS_REPLY;
		}
		if (mirroredCommits == 1) {
			return SINGLE_COMMIT_REPLY;
		}
		return MANY_COMMITS_REPLY.formatted(mirroredCommits);
	}

}
