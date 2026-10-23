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

	private void awaitQueuedWork() {
		serializer.submit(() -> {
		}).join();
	}

	private void commitWhileGitHubIsDown() {
		GITHUB.failWith(503);
		postCommit(1L, "x");
		awaitOnlyRecordStatus(CommitStatus.FAILED);
	}

	private void advanceTimeAndRunRecovery() {
		CLOCK.advance(Duration.ofSeconds(1));
		githubCircuitBreaker.reset();
		recovery.recover();
	}

	private void awaitAttemptCount(int expected) {
		await().atMost(timeout()).untilAsserted(() -> assertThat(onlyRecord().getAttemptCount()).isEqualTo(expected));
	}

	private void failUntilAttemptsAreExhausted() {
		commitWhileGitHubIsDown();
		for (int attempt = 2; attempt <= MAX_ATTEMPTS; attempt++) {
			advanceTimeAndRunRecovery();
			awaitAttemptCount(attempt);
		}
		awaitTelegramMessages(2);
	}

	private void reprocessOnceGitHubIsBack() {
		commitWhileGitHubIsDown();
		GITHUB.recover();
		advanceTimeAndRunRecovery();
		awaitOnlyRecordStatus(CommitStatus.SUCCESS);
	}

	private void simulateLostSuccessResponse() {
		postCommit(1L, "x");
		awaitOnlyRecordStatus(CommitStatus.SUCCESS);
		jdbcTemplate.update("update commit_log set status = 'FAILED', attempt_count = 1, updated_at = ?",
				Timestamp.from(CLOCK.instant()));
		advanceTimeAndRunRecovery();
		awaitOnlyRecordStatus(CommitStatus.SUCCESS);
	}

	@Test
	void shouldMarkRecordAsFailedWhenGitHubIsDown() {
		commitWhileGitHubIsDown();

		assertThat(onlyRecord().getStatus()).isEqualTo(CommitStatus.FAILED);
	}

	@Test
	void shouldKeepTheSequenceNumberWhenGitHubIsDown() {
		commitWhileGitHubIsDown();

		assertThat(onlyRecord().getSequenceNumber()).isEqualTo(1L);
	}

	@Test
	void shouldCountTheFirstAttemptWhenGitHubIsDown() {
		commitWhileGitHubIsDown();

		assertThat(onlyRecord().getAttemptCount()).isEqualTo(1);
	}

	@Test
	void shouldTellUserItWillRetryWhenGitHubIsDown() {
		commitWhileGitHubIsDown();

		awaitTelegramMessages(1);
		assertThat(TELEGRAM.sent().get(0).text()).contains("Commit 1").contains("tentar novamente");
	}

	@Test
	void shouldMarkFailedRecordAsSuccessOnceGitHubIsBack() {
		reprocessOnceGitHubIsBack();

		assertThat(onlyRecord().getStatus()).isEqualTo(CommitStatus.SUCCESS);
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

		assertThat(onlyRecord().getStatus()).isEqualTo(CommitStatus.FAILED);
	}

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

		advanceTimeAndRunRecovery();
		awaitQueuedWork();

		assertThat(onlyRecord().getAttemptCount()).isEqualTo(MAX_ATTEMPTS);
	}

	@Test
	void shouldLeaveExhaustedRecordAsFailed() {
		failUntilAttemptsAreExhausted();

		assertThat(onlyRecord().getStatus()).isEqualTo(CommitStatus.FAILED);
	}

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
