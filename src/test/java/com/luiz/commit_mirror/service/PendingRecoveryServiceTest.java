package com.luiz.commit_mirror.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
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

	private final CommitLogRepository repository = mock(CommitLogRepository.class);

	private final LogWriteSerializer serializer = mock(LogWriteSerializer.class);

	private final CommitProcessingService processing = mock(CommitProcessingService.class);

	private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

	private PendingRecoveryService service(boolean enabled) {
		AppProperties base = TestProperties.defaults();
		AppProperties.Recovery recovery = new AppProperties.Recovery(enabled, Duration.ofSeconds(60),
				Duration.ofMinutes(5), 3);
		return new PendingRecoveryService(repository, serializer, processing, clock,
				new AppProperties(base.telegram(), base.github(), base.timeZone(), recovery, base.resilience()));
	}

	private CommitLog log(long id, long sequence) {
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

	@Test
	void shouldReprocessStalePendingOnStartup() {
		serializerRunsTasksInline();
		when(repository.findByStatusAndCreatedAtBeforeOrderBySequenceNumber(eq(CommitStatus.PENDING), any()))
			.thenReturn(List.of(log(1L, 1L)));

		service(true).onStartup();

		verify(processing).process(1L);
	}

	@Test
	void shouldNotReprocessFreshPending() {
		service(true).recover();

		ArgumentCaptor<Instant> threshold = ArgumentCaptor.forClass(Instant.class);
		verify(repository).findByStatusAndCreatedAtBeforeOrderBySequenceNumber(eq(CommitStatus.PENDING),
				threshold.capture());
		assertThat(threshold.getValue()).isEqualTo(NOW.minus(Duration.ofMinutes(5)));
	}

	@Test
	void shouldReprocessFailedRecordsUpToMaxAttempts() {
		serializerRunsTasksInline();
		when(repository.findByStatusAndAttemptCountLessThanAndUpdatedAtBeforeOrderBySequenceNumber(
				eq(CommitStatus.FAILED), eq(3), any()))
			.thenReturn(List.of(log(2L, 2L)));

		service(true).recover();

		verify(processing).process(2L);
	}

	@Test
	void shouldProcessPendingAndFailedInSequenceOrder() {
		serializerRunsTasksInline();
		when(repository.findByStatusAndCreatedAtBeforeOrderBySequenceNumber(eq(CommitStatus.PENDING), any()))
			.thenReturn(List.of(log(30L, 3L)));
		when(repository.findByStatusAndAttemptCountLessThanAndUpdatedAtBeforeOrderBySequenceNumber(
				eq(CommitStatus.FAILED), anyInt(), any()))
			.thenReturn(List.of(log(10L, 1L), log(20L, 2L)));

		service(true).recover();

		InOrder order = inOrder(processing);
		order.verify(processing).process(10L);
		order.verify(processing).process(20L);
		order.verify(processing).process(30L);
	}

	@Test
	void shouldDoNothingWhenRecoveryIsDisabled() {
		service(false).recover();

		verifyNoInteractions(repository, serializer, processing);
	}

}
