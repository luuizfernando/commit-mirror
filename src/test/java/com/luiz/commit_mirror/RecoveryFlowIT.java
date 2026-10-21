package com.luiz.commit_mirror;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.luiz.commit_mirror.domain.CommitLog;
import com.luiz.commit_mirror.domain.CommitStatus;
import com.luiz.commit_mirror.service.PendingRecoveryService;

class RecoveryFlowIT extends AbstractFlowIT {

	@Autowired
	private PendingRecoveryService recovery;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private CommitLog only() {
		return repository.findAll().get(0);
	}

	@Test
	void shouldMarkFailedNotifyAndKeepSequenceWhenGitHubIsDown() {
		GITHUB.failWith(503);

		postCommit(1L, "x");

		await().atMost(timeout()).untilAsserted(() -> assertThat(repository.findAll()).extracting(CommitLog::getStatus)
			.containsExactly(CommitStatus.FAILED));
		assertThat(only().getSequenceNumber()).isEqualTo(1L);
		assertThat(only().getAttemptCount()).isEqualTo(1);
		await().atMost(timeout()).untilAsserted(() -> assertThat(TELEGRAM.sent()).hasSize(1));
		assertThat(TELEGRAM.sent().get(0).text()).contains("Commit 1").contains("tentar novamente");
	}

	@Test
	void shouldReprocessFailedRecordKeepingTheSameNumberOnceGitHubIsBack() {
		GITHUB.failWith(503);
		postCommit(1L, "x");
		await().atMost(timeout()).untilAsserted(() -> assertThat(only().getStatus()).isEqualTo(CommitStatus.FAILED));

		GITHUB.recover();
		githubCircuitBreaker.reset();
		recovery.recover();

		await().atMost(timeout()).untilAsserted(() -> assertThat(only().getStatus()).isEqualTo(CommitStatus.SUCCESS));
		assertThat(only().getSequenceNumber()).isEqualTo(1L);
		assertThat(GITHUB.fileContent("logs/" + only().getCommitDate().toString().substring(0, 7) + ".md"))
			.isEqualTo("Commit 1\n");
		assertThat(TELEGRAM.sent()).extracting(sent -> sent.text()).last().isEqualTo("Commit 1 registrado.");
	}

	@Test
	void shouldGiveUpAfterMaxAttemptsAndNotifyOnlyOnTheFinalFailure() {
		GITHUB.failWith(503);
		postCommit(1L, "x");
		await().atMost(timeout()).untilAsserted(() -> assertThat(only().getAttemptCount()).isEqualTo(1));

		for (int attempt = 2; attempt <= 3; attempt++) {
			githubCircuitBreaker.reset();
			recovery.recover();
			int expected = attempt;
			await().atMost(timeout()).untilAsserted(() -> assertThat(only().getAttemptCount()).isEqualTo(expected));
		}
		githubCircuitBreaker.reset();
		recovery.recover();

		await().atMost(timeout()).untilAsserted(() -> assertThat(TELEGRAM.sent()).hasSize(2));
		assertThat(TELEGRAM.sent().get(1).text()).contains("definitivamente");
		assertThat(only().getAttemptCount()).isEqualTo(3);
		assertThat(only().getStatus()).isEqualTo(CommitStatus.FAILED);
	}

	@Test
	void shouldNotAppendTheLineTwiceWhenItIsAlreadyInTheFile() {
		postCommit(1L, "x");
		await().atMost(timeout()).untilAsserted(() -> assertThat(only().getStatus()).isEqualTo(CommitStatus.SUCCESS));
		String path = "logs/" + only().getCommitDate().toString().substring(0, 7) + ".md";
		// the PUT succeeded but the response was lost: the record never left PENDING/FAILED
		jdbcTemplate.update("update commit_log set status = 'FAILED', attempt_count = 1, updated_at = now() - interval '10 seconds'");

		recovery.recover();

		await().atMost(timeout()).untilAsserted(() -> assertThat(only().getStatus()).isEqualTo(CommitStatus.SUCCESS));
		assertThat(GITHUB.fileContent(path)).isEqualTo("Commit 1\n");
		assertThat(GITHUB.commits()).hasSize(1);
	}

}
