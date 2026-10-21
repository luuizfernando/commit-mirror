package com.luiz.commit_mirror.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionOperations;

import com.luiz.commit_mirror.TestProperties;
import com.luiz.commit_mirror.domain.CommitLog;
import com.luiz.commit_mirror.repository.CommitLogRepository;

class CommitLogServiceTest {

	private final CommitLogRepository repository = mock(CommitLogRepository.class);

	private Clock clock = Clock.fixed(Instant.parse("2026-10-21T15:00:00Z"), ZoneOffset.UTC);

	private CommitLogService service() {
		return new CommitLogService(repository, new CommitDateResolver(TestProperties.defaults()), clock,
				TestProperties.defaults(), TransactionOperations.withoutTransaction());
	}

	private void saveReturnsArgument() {
		when(repository.saveAndFlush(any(CommitLog.class))).thenAnswer(invocation -> invocation.getArgument(0));
	}

	@Test
	void shouldAssignSequenceOneOnFirstCommit() {
		saveReturnsArgument();
		when(repository.findMaxSequenceNumber()).thenReturn(0L);

		CommitLog log = service().register(10L, 100L, "typed").orElseThrow();

		assertThat(log.getSequenceNumber()).isEqualTo(1L);
		assertThat(log.getTelegramUpdateId()).isEqualTo(10L);
		assertThat(log.getChatId()).isEqualTo(100L);
		assertThat(log.getMessage()).isEqualTo("typed");
		assertThat(log.getCommitDate()).isEqualTo(LocalDate.of(2026, 10, 21));
	}

	@Test
	void shouldIncrementSequenceForEachCommit() {
		saveReturnsArgument();
		when(repository.findMaxSequenceNumber()).thenReturn(4L);

		assertThat(service().register(10L, 100L, "x").orElseThrow().getSequenceNumber()).isEqualTo(5L);
	}

	@Test
	void shouldKeepIncrementingAcrossDaysAndMonths() {
		saveReturnsArgument();
		when(repository.findMaxSequenceNumber()).thenReturn(41L);
		clock = Clock.fixed(Instant.parse("2027-01-01T12:00:00Z"), ZoneOffset.UTC);

		CommitLog log = service().register(10L, 100L, "x").orElseThrow();

		assertThat(log.getSequenceNumber()).isEqualTo(42L);
		assertThat(log.getCommitDate()).isEqualTo(LocalDate.of(2027, 1, 1));
	}

	@Test
	void shouldReturnDuplicateWhenUpdateIdWasAlreadyProcessed() {
		when(repository.existsByTelegramUpdateId(10L)).thenReturn(true);

		Optional<CommitLog> result = service().register(10L, 100L, "x");

		assertThat(result).isEmpty();
		verify(repository, never()).saveAndFlush(any());
	}

	@Test
	void shouldRetryWhenSequenceCollides() {
		when(repository.findMaxSequenceNumber()).thenReturn(5L, 6L);
		when(repository.saveAndFlush(any(CommitLog.class))).thenThrow(new DataIntegrityViolationException("dup"))
			.thenAnswer(invocation -> invocation.getArgument(0));

		CommitLog log = service().register(10L, 100L, "x").orElseThrow();

		assertThat(log.getSequenceNumber()).isEqualTo(7L);
	}

	@Test
	void shouldGiveUpAfterMaxCollisionRetries() {
		when(repository.findMaxSequenceNumber()).thenReturn(5L);
		when(repository.saveAndFlush(any(CommitLog.class))).thenThrow(new DataIntegrityViolationException("dup"));

		assertThatThrownBy(() -> service().register(10L, 100L, "x")).isInstanceOf(IllegalStateException.class);
		verify(repository, times(TestProperties.resilience().sequenceMaxRetries())).saveAndFlush(any());
	}

	@Test
	void shouldTreatConcurrentInsertOfSameUpdateIdAsDuplicate() {
		when(repository.existsByTelegramUpdateId(10L)).thenReturn(false, true);
		when(repository.saveAndFlush(any(CommitLog.class))).thenThrow(new DataIntegrityViolationException("dup"));

		assertThat(service().register(10L, 100L, "x")).isEmpty();
	}

}
