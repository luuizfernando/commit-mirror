package com.luiz.commit_mirror.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class CommitLogTest {

	private static final Instant CREATED = Instant.parse("2026-10-21T15:00:00Z");

	private static final Instant LATER = Instant.parse("2026-10-21T15:05:00Z");

	private static CommitLog newLog() {
		return CommitLog.create(10L, 100L, "typed", LocalDate.of(2026, 10, 21), 7L, CREATED);
	}

	private static CommitLog succeededLog() {
		CommitLog log = newLog();
		log.markSuccess("abc", CREATED);
		return log;
	}

	private static CommitLog failedLog() {
		CommitLog log = newLog();
		log.markFailed(CREATED);
		return log;
	}

	// creation

	@Test
	void shouldStartAsPending() {
		assertThat(newLog().getStatus()).isEqualTo(CommitStatus.PENDING);
	}

	@Test
	void shouldStartWithZeroAttempts() {
		assertThat(newLog().getAttemptCount()).isZero();
	}

	@Test
	void shouldStartWithoutCommitSha() {
		assertThat(newLog().getGithubCommitSha()).isNull();
	}

	@Test
	void shouldKeepTheGivenSequenceNumber() {
		assertThat(newLog().getSequenceNumber()).isEqualTo(7L);
	}

	@Test
	void shouldStampCreationTime() {
		assertThat(newLog().getCreatedAt()).isEqualTo(CREATED);
	}

	@Test
	void shouldStartWithUpdateTimeEqualToCreationTime() {
		assertThat(newLog().getUpdatedAt()).isEqualTo(CREATED);
	}

	// success

	@Test
	void shouldMoveToSuccessWhenMarkedAsSuccess() {
		CommitLog log = newLog();

		log.markSuccess("abc123", LATER);

		assertThat(log.getStatus()).isEqualTo(CommitStatus.SUCCESS);
	}

	@Test
	void shouldStoreCommitShaWhenMarkedAsSuccess() {
		CommitLog log = newLog();

		log.markSuccess("abc123", LATER);

		assertThat(log.getGithubCommitSha()).isEqualTo("abc123");
	}

	@Test
	void shouldStampUpdateTimeWhenMarkedAsSuccess() {
		CommitLog log = newLog();

		log.markSuccess("abc123", LATER);

		assertThat(log.getUpdatedAt()).isEqualTo(LATER);
	}

	// failure

	@Test
	void shouldMoveToFailedWhenMarkedAsFailed() {
		CommitLog log = newLog();

		log.markFailed(LATER);

		assertThat(log.getStatus()).isEqualTo(CommitStatus.FAILED);
	}

	@Test
	void shouldCountTheAttemptWhenMarkedAsFailed() {
		CommitLog log = newLog();

		log.markFailed(LATER);

		assertThat(log.getAttemptCount()).isEqualTo(1);
	}

	@Test
	void shouldStampUpdateTimeWhenMarkedAsFailed() {
		CommitLog log = newLog();

		log.markFailed(LATER);

		assertThat(log.getUpdatedAt()).isEqualTo(LATER);
	}

	@Test
	void shouldAccumulateAttemptsAcrossReprocessing() {
		CommitLog log = failedLog();
		log.reopen(LATER);

		log.markFailed(LATER);

		assertThat(log.getAttemptCount()).isEqualTo(2);
	}

	// reopen

	@Test
	void shouldMoveFailedRecordBackToPendingWhenReopened() {
		CommitLog log = failedLog();

		log.reopen(LATER);

		assertThat(log.getStatus()).isEqualTo(CommitStatus.PENDING);
	}

	@Test
	void shouldKeepAttemptCountWhenReopened() {
		CommitLog log = failedLog();

		log.reopen(LATER);

		assertThat(log.getAttemptCount()).isEqualTo(1);
	}

	@Test
	void shouldStampUpdateTimeWhenReopened() {
		CommitLog log = failedLog();

		log.reopen(LATER);

		assertThat(log.getUpdatedAt()).isEqualTo(LATER);
	}

	// invalid transitions

	@Test
	void shouldRejectSuccessWhenAlreadySucceeded() {
		CommitLog log = succeededLog();

		assertThatThrownBy(() -> log.markSuccess("def", LATER)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void shouldRejectFailureWhenAlreadySucceeded() {
		CommitLog log = succeededLog();

		assertThatThrownBy(() -> log.markFailed(LATER)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void shouldRejectSuccessWhenFailedWithoutReopening() {
		CommitLog log = failedLog();

		assertThatThrownBy(() -> log.markSuccess("abc", LATER)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void shouldRejectFailureWhenAlreadyFailed() {
		CommitLog log = failedLog();

		assertThatThrownBy(() -> log.markFailed(LATER)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void shouldRejectReopenWhenPending() {
		CommitLog log = newLog();

		assertThatThrownBy(() -> log.reopen(LATER)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void shouldRejectReopenWhenSucceeded() {
		CommitLog log = succeededLog();

		assertThatThrownBy(() -> log.reopen(LATER)).isInstanceOf(IllegalStateException.class);
	}

}
