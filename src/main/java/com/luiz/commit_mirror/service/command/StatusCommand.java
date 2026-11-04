package com.luiz.commit_mirror.service.command;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.luiz.commit_mirror.domain.MostActiveDays;
import com.luiz.commit_mirror.integration.TelegramClient;
import com.luiz.commit_mirror.service.CommitLogService;

@Component
public class StatusCommand implements BotCommand {

	private static final Logger LOGGER = LoggerFactory.getLogger(StatusCommand.class);

	private static final String NO_COMMITS_REPLY = "Nenhum commit salvo ainda.";

	private static final String SINGLE_COMMIT_REPLY = "Você já salvou 1 commit com o Commit Mirror.";

	private static final String MANY_COMMITS_REPLY = "Você já salvou %d commits com o Commit Mirror.";

	private static final String SINGLE_MOST_ACTIVE_DAY = "Seu dia mais ativo foi %s, com %s.";

	private static final String TIED_MOST_ACTIVE_DAYS = "Seus dias mais ativos foram %s, com %s cada.";

	private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

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

	private String reply(long mirroredCommits) {
		if (mirroredCommits == 0) {
			return NO_COMMITS_REPLY;
		}
		String countLine = countLine(mirroredCommits);
		Optional<MostActiveDays> mostActiveDays = commitLogService.findMostActiveDays();
		return mostActiveDays.map(days -> countLine + "\n" + mostActiveDaysLine(days)).orElse(countLine);
	}

	private static String countLine(long mirroredCommits) {
		if (mirroredCommits == 1) {
			return SINGLE_COMMIT_REPLY;
		}
		return MANY_COMMITS_REPLY.formatted(mirroredCommits);
	}

	private static String mostActiveDaysLine(MostActiveDays mostActiveDays) {
		List<LocalDate> dates = mostActiveDays.dates();
		String template = dates.size() == 1 ? SINGLE_MOST_ACTIVE_DAY : TIED_MOST_ACTIVE_DAYS;
		return template.formatted(joinDates(dates), commitsQuantity(mostActiveDays.commits()));
	}

	private static String joinDates(List<LocalDate> dates) {
		List<String> formatted = dates.stream().map(DAY_FORMAT::format).toList();
		if (formatted.size() == 1) {
			return formatted.getFirst();
		}
		String allButLast = String.join(", ", formatted.subList(0, formatted.size() - 1));
		return allButLast + " e " + formatted.getLast();
	}

	private static String commitsQuantity(long commits) {
		return commits == 1 ? "1 commit" : commits + " commits";
	}

}
