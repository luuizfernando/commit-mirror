package com.luiz.commit_mirror.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import com.luiz.commit_mirror.config.AppProperties;
import com.luiz.commit_mirror.domain.CommitLog;
import com.luiz.commit_mirror.repository.CommitLogRepository;

@Service
public class CommitLogService {

	private static final Logger LOGGER = LoggerFactory.getLogger(CommitLogService.class);

	private final CommitLogRepository repository;

	private final CommitDateResolver dateResolver;

	private final Clock clock;

	private final int maxAttempts;

	private final TransactionOperations newTransaction;

	public CommitLogService(CommitLogRepository repository, CommitDateResolver dateResolver, Clock clock,
			AppProperties properties, @Qualifier("newTransaction") TransactionOperations newTransaction) {
		this.repository = repository;
		this.dateResolver = dateResolver;
		this.clock = clock;
		this.maxAttempts = properties.resilience().sequenceMaxRetries();
		this.newTransaction = newTransaction;
	}

	/**
	 * Records a new PENDING commit with the next global sequence number.
	 * @return the record, or empty when this Telegram update was already registered
	 */
	public Optional<CommitLog> register(long updateId, long chatId, String message) {
		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			try {
				return newTransaction.execute(status -> insert(updateId, chatId, message));
			}
			catch (DataIntegrityViolationException ex) {
				if (repository.existsByTelegramUpdateId(updateId)) {
					return Optional.empty();
				}
				LOGGER.warn("Sequence number collision, updateId={}, attempt={}", updateId, attempt);
			}
		}
		throw new IllegalStateException("Could not allocate a sequence number after " + maxAttempts + " attempts");
	}

	private Optional<CommitLog> insert(long updateId, long chatId, String message) {
		if (repository.existsByTelegramUpdateId(updateId)) {
			return Optional.empty();
		}
		Instant now = clock.instant();
		long sequence = repository.findMaxSequenceNumber() + 1;
		CommitLog log = CommitLog.create(updateId, chatId, message, dateResolver.resolve(now), sequence, now);
		return Optional.of(repository.saveAndFlush(log));
	}

}
