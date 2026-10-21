package com.luiz.commit_mirror.service;

import java.time.Clock;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.luiz.commit_mirror.config.AppProperties;
import com.luiz.commit_mirror.domain.CommitLog;
import com.luiz.commit_mirror.domain.CommitStatus;
import com.luiz.commit_mirror.integration.GitHubPermanentException;
import com.luiz.commit_mirror.integration.GitHubTransientException;
import com.luiz.commit_mirror.integration.TelegramApiException;
import com.luiz.commit_mirror.integration.TelegramClient;
import com.luiz.commit_mirror.repository.CommitLogRepository;

/**
 * Sends a registered commit to GitHub and records the outcome. Safe to call again for the same record: a finished
 * one is skipped, and a failed one is retried keeping its sequence number.
 */
@Service
public class CommitProcessingService {

	private static final Logger LOGGER = LoggerFactory.getLogger(CommitProcessingService.class);

	private final CommitLogRepository repository;

	private final GitHubLogWriter writer;

	private final CommitMessageFormatter messageFormatter;

	private final LogLineFormatter lineFormatter;

	private final LogFilePathResolver pathResolver;

	private final TelegramClient telegramClient;

	private final Clock clock;

	private final int maxAttempts;

	public CommitProcessingService(CommitLogRepository repository, GitHubLogWriter writer,
			CommitMessageFormatter messageFormatter, LogLineFormatter lineFormatter, LogFilePathResolver pathResolver,
			TelegramClient telegramClient, Clock clock, AppProperties properties) {
		this.repository = repository;
		this.writer = writer;
		this.messageFormatter = messageFormatter;
		this.lineFormatter = lineFormatter;
		this.pathResolver = pathResolver;
		this.telegramClient = telegramClient;
		this.clock = clock;
		this.maxAttempts = properties.recovery().maxAttempts();
	}

	public void process(long commitLogId) {
		Optional<CommitLog> found = repository.findById(commitLogId);
		if (found.isEmpty()) {
			LOGGER.warn("Commit log not found, commitLogId={}", commitLogId);
			return;
		}
		CommitLog log = found.get();
		if (log.getStatus() == CommitStatus.SUCCESS) {
			LOGGER.debug("Commit already registered, sequenceNumber={}", log.getSequenceNumber());
			return;
		}
		if (log.getStatus() == CommitStatus.FAILED) {
			log.reopen(clock.instant());
		}
		try {
			String sha = writer.append(pathResolver.resolve(log.getCommitDate()),
					lineFormatter.format(log.getSequenceNumber()), messageFormatter.format(log.getSequenceNumber()),
					log.getCreatedAt());
			log.markSuccess(sha, clock.instant());
			repository.save(log);
			LOGGER.info("Commit registered on GitHub, sequenceNumber={}", log.getSequenceNumber());
			notifyUser(log, "Commit " + log.getSequenceNumber() + " registrado.");
		}
		catch (GitHubTransientException | GitHubPermanentException ex) {
			LOGGER.warn("GitHub write failed, sequenceNumber={}, reason={}", log.getSequenceNumber(),
					ex.getClass().getSimpleName());
			fail(log, reasonFor(ex));
		}
		catch (RuntimeException ex) {
			LOGGER.error("Unexpected failure registering commit, sequenceNumber={}", log.getSequenceNumber(), ex);
			fail(log, "erro inesperado");
		}
	}

	private void fail(CommitLog log, String reason) {
		log.markFailed(clock.instant());
		repository.save(log);
		long sequence = log.getSequenceNumber();
		if (log.getAttemptCount() >= maxAttempts) {
			notifyUser(log, "Commit " + sequence + " falhou definitivamente (" + reason + ").");
		}
		else if (log.getAttemptCount() == 1) {
			notifyUser(log, "Commit " + sequence + " não foi registrado agora (" + reason
					+ "). Vou tentar novamente automaticamente.");
		}
	}

	private static String reasonFor(RuntimeException ex) {
		return ex instanceof GitHubPermanentException
				? "o GitHub recusou a requisição, verifique o token e as permissões" : "GitHub indisponível";
	}

	private void notifyUser(CommitLog log, String text) {
		try {
			telegramClient.sendMessage(log.getChatId(), text);
		}
		catch (TelegramApiException ex) {
			LOGGER.warn("Could not notify the user, sequenceNumber={}, reason={}", log.getSequenceNumber(),
					ex.getMessage());
		}
	}

}
