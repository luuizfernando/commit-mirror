package com.luiz.commit_mirror.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import com.luiz.commit_mirror.TestProperties;
import com.luiz.commit_mirror.config.AppProperties;
import com.luiz.commit_mirror.domain.CommitLog;
import com.luiz.commit_mirror.domain.CommitStatus;
import com.luiz.commit_mirror.repository.CommitLogRepository;

class PendingRecoveryServiceTest {

	private static final Instant NOW = Instant.parse("2026-10-21T16:00:00Z");

	private static final Duration MIN_AGE = Duration.ofMinutes(5);

	private static final int MAX_ATTEMPTS = 3;

	private final CommitLogRepository repository = mock(CommitLogRepository.class);

	private final LogWriteSerializer serializer = mock(LogWriteSerializer.class);

	private final CommitProcessingService processing = mock(CommitProcessingService.class);

	private final PendingRecoveryService enabledService = service(true);

	private PendingRecoveryService service(boolean enabled) {
		AppProperties.Recovery recovery = new AppProperties.Recovery(enabled, Duration.ofSeconds(60), MIN_AGE,
				MAX_ATTEMPTS);
		return new PendingRecoveryService(repository, serializer, processing, Clock.fixed(NOW, ZoneOffset.UTC),
				TestProperties.withRecovery(recovery));
	}

	private static CommitLog log(long id, long sequence) {
		CommitLog log = CommitLog.create(id, 100L, "x", LocalDate.of(2026, 10, 21), sequence, NOW);
		ReflectionTestUtils.setField(log, "id", id);
		return log;
	}

	private void serializerRunsTasksInline() {
		when(serializer.submit(any(Runnable.class))).thenAnswer(invocation -> {
			((Runnable) invocation.getArgument(0)).run();
			return CompletableFuture.completedFuture(null);
		});
	}

	private void stalePendingAre(CommitLog... logs) {
		when(repository.findByStatusAndCreatedAtBeforeOrderBySequenceNumber(eq(CommitStatus.PENDING), any()))
			.thenReturn(List.of(logs));
	}

	private void retryableFailedAre(CommitLog... logs) {
		when(repository.findByStatusAndAttemptCountLessThanAndUpdatedAtBeforeOrderBySequenceNumber(
				eq(CommitStatus.FAILED), anyInt(), any()))
			.thenReturn(List.of(logs));
	}

	@Test
	void shouldReprocessStalePendingOnStartup() {
		serializerRunsTasksInline();
		stalePendingAre(log(1L, 1L));

		enabledService.onStartup();

		verify(processing).process(1L);
	}

	@Test
	void shouldReprocessRetryableFailedRecords() {
		serializerRunsTasksInline();
		retryableFailedAre(log(2L, 2L));

		enabledService.recover();

		verify(processing).process(2L);
	}

	@Test
	void shouldProcessPendingAndFailedInSequenceOrder() {
		serializerRunsTasksInline();
		stalePendingAre(log(30L, 3L));
		retryableFailedAre(log(10L, 1L), log(20L, 2L));

		enabledService.recover();

		InOrder order = inOrder(processing);
		order.verify(processing).process(10L);
		order.verify(processing).process(20L);
		order.verify(processing).process(30L);
	}

	@Test
	void shouldQueueEachCandidateThroughTheSerializer() {
		stalePendingAre(log(1L, 1L));
		retryableFailedAre(log(2L, 2L));

		enabledService.recover();

		verify(serializer, times(2)).submit(any(Runnable.class));
	}

	@Test
	void shouldNotProcessOutsideTheSerializer() {
		stalePendingAre(log(1L, 1L));

		enabledService.recover();

		verify(processing, never()).process(1L);
	}

	@Test
	void shouldOnlyLookForPendingCreatedBeforeTheMinimumAge() {
		enabledService.recover();

		ArgumentCaptor<Instant> threshold = ArgumentCaptor.forClass(Instant.class);
		verify(repository).findByStatusAndCreatedAtBeforeOrderBySequenceNumber(eq(CommitStatus.PENDING),
				threshold.capture());
		assertThat(threshold.getValue()).isEqualTo(NOW.minus(MIN_AGE));
	}

	@Test
	void shouldOnlyLookForFailedUpdatedBeforeTheMinimumAge() {
		enabledService.recover();

		ArgumentCaptor<Instant> threshold = ArgumentCaptor.forClass(Instant.class);
		verify(repository).findByStatusAndAttemptCountLessThanAndUpdatedAtBeforeOrderBySequenceNumber(
				eq(CommitStatus.FAILED), anyInt(), threshold.capture());
		assertThat(threshold.getValue()).isEqualTo(NOW.minus(MIN_AGE));
	}

	@Test
	void shouldOnlyLookForFailedWithAttemptsBelowTheMaximum() {
		enabledService.recover();

		verify(repository).findByStatusAndAttemptCountLessThanAndUpdatedAtBeforeOrderBySequenceNumber(
				eq(CommitStatus.FAILED), eq(MAX_ATTEMPTS), any());
	}

	@Test
	void shouldNotQueueAnythingWhenThereAreNoCandidates() {
		enabledService.recover();

		verifyNoInteractions(serializer);
	}

	@Test
	void shouldDoNothingWhenRecoveryIsDisabled() {
		service(false).recover();

		verifyNoInteractions(repository, serializer, processing);
	}

	@Test
	void shouldDoNothingOnStartupWhenRecoveryIsDisabled() {
		service(false).onStartup();

		verifyNoInteractions(repository, serializer, processing);
	}

}
