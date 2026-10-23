package com.luiz.commit_mirror;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.Timestamp;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.luiz.commit_mirror.domain.CommitStatus;
import com.luiz.commit_mirror.service.LogWriteSerializer;
import com.luiz.commit_mirror.service.PendingRecoveryService;
import com.luiz.commit_mirror.support.FakeTelegramServer.Sent;

class RecoveryFlowIT extends AbstractFlowIT {

	private static final int MAX_ATTEMPTS = 3;

	@Autowired
	private PendingRecoveryService recovery;

	@Autowired
	private LogWriteSerializer serializer;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	/** The worker is a single FIFO thread: once a no-op runs, everything queued before it has finished. */
	private void awaitQueuedWork() {
		serializer.submit(() -> {
		}).join();
	}

	private void commitWhileGitHubIsDown() {
		GITHUB.failWith(503);
		postCommit(1L, "x");
		awaitOnlyRecordStatus(CommitStatus.FAILED);
	}

	/** Moves time past the minimum age (0s here) so the record is eligible, then runs one recovery cycle. */
	private void runRecovery() {
		CLOCK.advance(Duration.ofSeconds(1));
		githubCircuitBreaker.reset();
		recovery.recover();
	}

	private void awaitAttemptCount(int expected) {
		await().atMost(timeout()).untilAsserted(() -> assertThat(only().getAttemptCount()).isEqualTo(expected));
	}

	private void failUntilAttemptsAreExhausted() {
		commitWhileGitHubIsDown();
		for (int attempt = 2; attempt <= MAX_ATTEMPTS; attempt++) {
			runRecovery();
			awaitAttemptCount(attempt);
		}
		awaitTelegramMessages(2);
	}

	private void reprocessOnceGitHubIsBack() {
		commitWhileGitHubIsDown();
		GITHUB.recover();
		runRecovery();
		awaitOnlyRecordStatus(CommitStatus.SUCCESS);
	}

	/** The PUT succeeded but its response was lost, so the record never left FAILED. */
	private void simulateLostSuccessResponse() {
		postCommit(1L, "x");
		awaitOnlyRecordStatus(CommitStatus.SUCCESS);
		jdbcTemplate.update("update commit_log set status = 'FAILED', attempt_count = 1, updated_at = ?",
				Timestamp.from(CLOCK.instant()));
		runRecovery();
		awaitOnlyRecordStatus(CommitStatus.SUCCESS);
	}

	// first failure

	@Test
	void shouldMarkRecordAsFailedWhenGitHubIsDown() {
		commitWhileGitHubIsDown();

		assertThat(only().getStatus()).isEqualTo(CommitStatus.FAILED);
	}

	@Test
	void shouldKeepTheSequenceNumberWhenGitHubIsDown() {
		commitWhileGitHubIsDown();

		assertThat(only().getSequenceNumber()).isEqualTo(1L);
	}

	@Test
	void shouldCountTheFirstAttemptWhenGitHubIsDown() {
		commitWhileGitHubIsDown();

		assertThat(only().getAttemptCount()).isEqualTo(1);
	}

	@Test
	void shouldTellUserItWillRetryWhenGitHubIsDown() {
		commitWhileGitHubIsDown();

		awaitTelegramMessages(1);
		assertThat(TELEGRAM.sent().get(0).text()).contains("Commit 1").contains("tentar novamente");
	}

	// recovery once GitHub is back

	@Test
	void shouldMarkFailedRecordAsSuccessOnceGitHubIsBack() {
		reprocessOnceGitHubIsBack();

		assertThat(only().getStatus()).isEqualTo(CommitStatus.SUCCESS);
	}

	@Test
	void shouldWriteTheOriginalNumberWhenReprocessing() {
		reprocessOnceGitHubIsBack();

		assertThat(GITHUB.fileContent(MONTH_FILE)).isEqualTo("Commit 1\n");
	}

	@Test
	void shouldConfirmToTheUserAfterReprocessing() {
		reprocessOnceGitHubIsBack();

		await().atMost(timeout())
			.untilAsserted(() -> assertThat(TELEGRAM.sent()).last().isEqualTo(new Sent(CHAT_ID, "Commit 1 registrado.")));
	}

	@Test
	void shouldNotReprocessFailedRecordBeforeTheMinimumAge() {
		commitWhileGitHubIsDown();
		GITHUB.recover();

		recovery.recover();
		awaitQueuedWork();

		assertThat(only().getStatus()).isEqualTo(CommitStatus.FAILED);
	}

	// giving up

	@Test
	void shouldNotifyOnlyOnTheFirstAndTheFinalFailure() {
		failUntilAttemptsAreExhausted();

		assertThat(TELEGRAM.sent()).hasSize(2);
	}

	@Test
	void shouldTellUserItGaveUpOnTheFinalFailure() {
		failUntilAttemptsAreExhausted();

		assertThat(TELEGRAM.sent().get(1).text()).contains("definitivamente");
	}

	@Test
	void shouldNotTryAgainAfterMaxAttempts() {
		failUntilAttemptsAreExhausted();

		runRecovery();
		awaitQueuedWork();

		assertThat(only().getAttemptCount()).isEqualTo(MAX_ATTEMPTS);
	}

	@Test
	void shouldLeaveExhaustedRecordAsFailed() {
		failUntilAttemptsAreExhausted();

		assertThat(only().getStatus()).isEqualTo(CommitStatus.FAILED);
	}

	// idempotency

	@Test
	void shouldNotAppendTheLineTwiceWhenItIsAlreadyInTheFile() {
		simulateLostSuccessResponse();

		assertThat(GITHUB.fileContent(MONTH_FILE)).isEqualTo("Commit 1\n");
	}

	@Test
	void shouldNotCreateASecondGitCommitWhenTheLineIsAlreadyInTheFile() {
		simulateLostSuccessResponse();

		assertThat(GITHUB.commits()).hasSize(1);
	}

}
