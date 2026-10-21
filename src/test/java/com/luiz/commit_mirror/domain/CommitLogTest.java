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

	@Test
	void shouldStartAsPending() {
		CommitLog log = newLog();

		assertThat(log.getStatus()).isEqualTo(CommitStatus.PENDING);
		assertThat(log.getAttemptCount()).isZero();
		assertThat(log.getGithubCommitSha()).isNull();
		assertThat(log.getSequenceNumber()).isEqualTo(7L);
		assertThat(log.getCreatedAt()).isEqualTo(CREATED);
		assertThat(log.getUpdatedAt()).isEqualTo(CREATED);
	}

	@Test
	void shouldMarkSuccessWithCommitSha() {
		CommitLog log = newLog();

		log.markSuccess("abc123", LATER);

		assertThat(log.getStatus()).isEqualTo(CommitStatus.SUCCESS);
		assertThat(log.getGithubCommitSha()).isEqualTo("abc123");
		assertThat(log.getUpdatedAt()).isEqualTo(LATER);
	}

	@Test
	void shouldMarkFailedAndCountTheAttempt() {
		CommitLog log = newLog();

		log.markFailed(LATER);

		assertThat(log.getStatus()).isEqualTo(CommitStatus.FAILED);
		assertThat(log.getAttemptCount()).isEqualTo(1);
		assertThat(log.getUpdatedAt()).isEqualTo(LATER);
	}

	@Test
	void shouldRejectSuccessWhenAlreadySucceeded() {
		CommitLog log = newLog();
		log.markSuccess("abc", LATER);

		assertThatThrownBy(() -> log.markSuccess("def", LATER)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> log.markFailed(LATER)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void shouldAllowReprocessingFailedRecordKeepingSequence() {
		CommitLog log = newLog();
		log.markFailed(LATER);

		log.reopen(LATER);

		assertThat(log.getStatus()).isEqualTo(CommitStatus.PENDING);
		assertThat(log.getSequenceNumber()).isEqualTo(7L);
		assertThat(log.getAttemptCount()).isEqualTo(1);
	}

	@Test
	void shouldRejectReopenUnlessFailed() {
		CommitLog log = newLog();

		assertThatThrownBy(() -> log.reopen(LATER)).isInstanceOf(IllegalStateException.class);
	}

}
