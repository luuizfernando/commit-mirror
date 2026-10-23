package com.luiz.commit_mirror.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
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

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionOperations;

import com.luiz.commit_mirror.TestProperties;
import com.luiz.commit_mirror.domain.CommitLog;
import com.luiz.commit_mirror.domain.CommitStatus;
import com.luiz.commit_mirror.repository.CommitLogRepository;

class CommitLogServiceTest {

	private static final Instant NOW = Instant.parse("2026-10-21T15:00:00Z");

	private static final long UPDATE_ID = 10L;

	private static final long CHAT_ID = 100L;

	private final CommitLogRepository repository = mock(CommitLogRepository.class);

	private final CommitLogService service = serviceAt(NOW);

	private CommitLogService serviceAt(Instant now) {
		return new CommitLogService(repository, new CommitDateResolver(TestProperties.defaults()),
				Clock.fixed(now, ZoneOffset.UTC), TestProperties.defaults(), TransactionOperations.withoutTransaction());
	}

	private void saveReturnsArgument() {
		when(repository.saveAndFlush(any(CommitLog.class))).thenAnswer(invocation -> invocation.getArgument(0));
	}

	private CommitLog registerFirstCommit(String message) {
		saveReturnsArgument();
		return service.register(UPDATE_ID, CHAT_ID, message).orElseThrow();
	}

	private void saveAlwaysCollides() {
		when(repository.saveAndFlush(any(CommitLog.class))).thenThrow(new DataIntegrityViolationException("dup"));
	}

	@Test
	void shouldAssignSequenceOneWhenThereAreNoCommits() {
		assertThat(registerFirstCommit("typed").getSequenceNumber()).isEqualTo(1L);
	}

	@Test
	void shouldAssignNextSequenceAfterTheCurrentMaximum() {
		saveReturnsArgument();
		when(repository.findMaxSequenceNumber()).thenReturn(4L);

		assertThat(service.register(UPDATE_ID, CHAT_ID, "x").orElseThrow().getSequenceNumber()).isEqualTo(5L);
	}

	@Test
	void shouldKeepIncrementingTheSequenceAcrossMonthsAndYears() {
		saveReturnsArgument();
		when(repository.findMaxSequenceNumber()).thenReturn(41L);

		CommitLog log = serviceAt(Instant.parse("2027-01-01T12:00:00Z")).register(UPDATE_ID, CHAT_ID, "x").orElseThrow();

		assertThat(log.getSequenceNumber()).isEqualTo(42L);
	}

	@Test
	void shouldStoreTheTelegramUpdateId() {
		assertThat(registerFirstCommit("typed").getTelegramUpdateId()).isEqualTo(UPDATE_ID);
	}

	@Test
	void shouldStoreTheChatId() {
		assertThat(registerFirstCommit("typed").getChatId()).isEqualTo(CHAT_ID);
	}

	@Test
	void shouldStoreTheTypedMessage() {
		assertThat(registerFirstCommit("typed").getMessage()).isEqualTo("typed");
	}

	@Test
	void shouldRegisterTheRecordAsPending() {
		assertThat(registerFirstCommit("typed").getStatus()).isEqualTo(CommitStatus.PENDING);
	}

	@Test
	void shouldStampCreationTimeFromTheClock() {
		assertThat(registerFirstCommit("typed").getCreatedAt()).isEqualTo(NOW);
	}

	@Test
	void shouldResolveCommitDateFromTheClock() {
		assertThat(registerFirstCommit("typed").getCommitDate()).isEqualTo(LocalDate.of(2026, 10, 21));
	}

	@Test
	void shouldResolveCommitDateInTheConfiguredZoneNearMidnight() {
		saveReturnsArgument();

		CommitLog log = serviceAt(Instant.parse("2026-11-01T02:30:00Z")).register(UPDATE_ID, CHAT_ID, "x").orElseThrow();

		assertThat(log.getCommitDate()).isEqualTo(LocalDate.of(2026, 10, 31));
	}

	@Test
	void shouldReturnEmptyWhenUpdateIdWasAlreadyRegistered() {
		when(repository.existsByTelegramUpdateId(UPDATE_ID)).thenReturn(true);

		assertThat(service.register(UPDATE_ID, CHAT_ID, "x")).isEmpty();
	}

	@Test
	void shouldNotSaveWhenUpdateIdWasAlreadyRegistered() {
		when(repository.existsByTelegramUpdateId(UPDATE_ID)).thenReturn(true);

		service.register(UPDATE_ID, CHAT_ID, "x");

		verify(repository, never()).saveAndFlush(any());
	}

	@Test
	void shouldRetryWithTheNextSequenceWhenSequenceCollides() {
		when(repository.findMaxSequenceNumber()).thenReturn(5L, 6L);
		when(repository.saveAndFlush(any(CommitLog.class))).thenThrow(new DataIntegrityViolationException("dup"))
			.thenAnswer(invocation -> invocation.getArgument(0));

		assertThat(service.register(UPDATE_ID, CHAT_ID, "x").orElseThrow().getSequenceNumber()).isEqualTo(7L);
	}

	@Test
	void shouldFailWhenSequenceKeepsCollidingUntilMaxRetries() {
		when(repository.findMaxSequenceNumber()).thenReturn(5L);
		saveAlwaysCollides();

		assertThatThrownBy(() -> service.register(UPDATE_ID, CHAT_ID, "x")).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void shouldTryExactlyMaxRetriesTimesBeforeGivingUp() {
		saveAlwaysCollides();

		catchThrowable(() -> service.register(UPDATE_ID, CHAT_ID, "x"));

		verify(repository, times(TestProperties.resilience().sequenceMaxRetries())).saveAndFlush(any());
	}

	@Test
	void shouldTreatConcurrentInsertOfSameUpdateIdAsDuplicate() {
		when(repository.existsByTelegramUpdateId(UPDATE_ID)).thenReturn(false, true);
		saveAlwaysCollides();

		assertThat(service.register(UPDATE_ID, CHAT_ID, "x")).isEmpty();
	}

	@Test
	void shouldNotRetryWhenCollisionComesFromTheSameUpdateId() {
		when(repository.existsByTelegramUpdateId(UPDATE_ID)).thenReturn(false, true);
		saveAlwaysCollides();

		service.register(UPDATE_ID, CHAT_ID, "x");

		verify(repository, times(1)).saveAndFlush(any());
	}

}
