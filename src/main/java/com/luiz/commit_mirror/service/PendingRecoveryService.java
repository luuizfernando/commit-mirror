package com.luiz.commit_mirror.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.luiz.commit_mirror.config.AppProperties;
import com.luiz.commit_mirror.domain.CommitLog;
import com.luiz.commit_mirror.domain.CommitStatus;
import com.luiz.commit_mirror.repository.CommitLogRepository;

@Service
public class PendingRecoveryService {

	private static final Logger LOGGER = LoggerFactory.getLogger(PendingRecoveryService.class);

	private final CommitLogRepository repository;

	private final LogWriteSerializer serializer;

	private final CommitProcessingService processing;

	private final Clock clock;

	private final AppProperties.Recovery settings;

	public PendingRecoveryService(CommitLogRepository repository, LogWriteSerializer serializer,
			CommitProcessingService processing, Clock clock, AppProperties properties) {
		this.repository = repository;
		this.serializer = serializer;
		this.processing = processing;
		this.clock = clock;
		this.settings = properties.recovery();
	}

	@EventListener(ApplicationReadyEvent.class)
	public void onStartup() {
		recover();
	}

	@Scheduled(fixedDelayString = "${app.recovery.interval}", initialDelayString = "${app.recovery.interval}")
	public void recover() {
		if (!settings.enabled()) {
			return;
		}
		Instant threshold = clock.instant().minus(settings.minAge());
		List<CommitLog> candidates = new ArrayList<>(
				repository.findByStatusAndCreatedAtBeforeOrderBySequenceNumber(CommitStatus.PENDING, threshold));
		candidates.addAll(repository.findByStatusAndAttemptCountLessThanAndUpdatedAtBeforeOrderBySequenceNumber(
				CommitStatus.FAILED, settings.maxAttempts(), threshold));
		candidates.sort(Comparator.comparingLong(CommitLog::getSequenceNumber));
		if (!candidates.isEmpty()) {
			LOGGER.info("Recovering unfinished commits, count={}", candidates.size());
		}
		for (CommitLog candidate : candidates) {
			long id = candidate.getId();
			serializer.submit(() -> processing.process(id));
		}
	}

}
