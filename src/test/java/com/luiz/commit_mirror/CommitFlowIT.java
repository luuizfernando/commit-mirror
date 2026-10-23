package com.luiz.commit_mirror;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.luiz.commit_mirror.domain.CommitLog;
import com.luiz.commit_mirror.domain.CommitStatus;
import com.luiz.commit_mirror.support.FakeGitHubServer.Commit;
import com.luiz.commit_mirror.support.FakeTelegramServer.Sent;

class CommitFlowIT extends AbstractFlowIT {

	private static final String TYPED_TEXT = "never-leaves-the-database";

	private static final int CONCURRENT_COMMITS = 10;

	private Commit firstCommitToGitHub() {
		postCommit(1L, TYPED_TEXT);
		awaitGitHubCommits(1);
		return GITHUB.commits().get(0);
	}

	private void commitTwice() {
		postCommit(1L, "one");
		awaitGitHubCommits(1);
		postCommit(2L, "two");
		awaitGitHubCommits(2);
	}

	private void postConcurrentCommits() {
		ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_COMMITS);
		try {
			List<CompletableFuture<Integer>> posts = IntStream.rangeClosed(1, CONCURRENT_COMMITS)
				.mapToObj(i -> CompletableFuture.supplyAsync(() -> postCommit(i, "concurrent"), pool))
				.toList();
			posts.forEach(CompletableFuture::join);
			awaitGitHubCommits(CONCURRENT_COMMITS);
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	void shouldAcceptCommitWebhookWith200() {
		assertThat(postCommit(1L, TYPED_TEXT)).isEqualTo(200);
	}

	@Test
	void shouldCreateMonthFileWithTheFirstLine() {
		firstCommitToGitHub();

		assertThat(GITHUB.fileContent(MONTH_FILE)).isEqualTo("Commit 1\n");
	}

	@Test
	void shouldUseCommitNAsTheGitCommitMessage() {
		assertThat(firstCommitToGitHub().message()).isEqualTo("Commit 1");
	}

	@Test
	void shouldReplyConfirmationToTheUser() {
		firstCommitToGitHub();

		await().atMost(timeout())
			.untilAsserted(() -> assertThat(TELEGRAM.sent()).containsExactly(new Sent(CHAT_ID, "Commit 1 registrado.")));
	}

	@Test
	void shouldAppendTheSecondLineToTheSameMonthFile() {
		commitTwice();

		assertThat(GITHUB.fileContent(MONTH_FILE)).isEqualTo("Commit 1\nCommit 2\n");
	}

	@Test
	void shouldCreateOneGitCommitPerCommitInOrder() {
		commitTwice();

		assertThat(GITHUB.commits()).extracting(Commit::message).containsExactly("Commit 1", "Commit 2");
	}

	@Test
	void shouldStoreSuccessWithTheGitHubSha() {
		postCommit(1L, TYPED_TEXT);
		awaitOnlyRecordStatus(CommitStatus.SUCCESS);

		assertThat(onlyRecord().getGithubCommitSha()).isNotBlank();
	}

	@Test
	void shouldSendTheConfiguredAuthorIdentity() {
		Commit commit = firstCommitToGitHub();

		assertThat(commit.authorName() + " <" + commit.authorEmail() + ">")
			.isEqualTo("Test Author <author@example.com>");
	}

	@Test
	void shouldDateTheAuthorWithTheCreationInstant() {
		assertThat(firstCommitToGitHub().authorDate()).isEqualTo(NOON_IN_SAO_PAULO.toString());
	}

	@Test
	void shouldDateTheCommitterWithTheCreationInstant() {
		assertThat(firstCommitToGitHub().committerDate()).isEqualTo(NOON_IN_SAO_PAULO.toString());
	}

	@Test
	void shouldCommitToTheConfiguredBranch() {
		assertThat(firstCommitToGitHub().branch()).isEqualTo("main");
	}

	@Test
	void shouldAuthenticateWithTheConfiguredToken() {
		assertThat(firstCommitToGitHub().authorization()).isEqualTo("Bearer test-github-token");
	}

	@Test
	void shouldNeverSendTypedTextToGitHub() {
		assertThat(firstCommitToGitHub().rawBody()).doesNotContain(TYPED_TEXT);
	}

	@Test
	void shouldNeverWriteTypedTextToTheLogFile() {
		firstCommitToGitHub();

		assertThat(GITHUB.fileContent(MONTH_FILE)).doesNotContain(TYPED_TEXT);
	}

	@Test
	void shouldKeepTypedTextOnlyInTheDatabase() {
		firstCommitToGitHub();

		assertThat(onlyRecord().getMessage()).isEqualTo(TYPED_TEXT);
	}

	@Test
	void shouldAcceptRedeliveredUpdateWith200() {
		firstCommitToGitHub();

		assertThat(postCommit(1L, TYPED_TEXT)).isEqualTo(200);
	}

	@Test
	void shouldNotStoreRedeliveredUpdateAgain() {
		firstCommitToGitHub();

		postCommit(1L, TYPED_TEXT);

		assertThat(repository.count()).isEqualTo(1L);
	}

	@Test
	void shouldNotCommitRedeliveredUpdateAgain() {
		firstCommitToGitHub();

		postCommit(1L, TYPED_TEXT);

		assertThat(GITHUB.commits()).hasSize(1);
	}

	@Test
	void shouldWriteEveryConcurrentCommitExactlyOnceToTheFile() {
		postConcurrentCommits();

		assertThat(GITHUB.fileContent(MONTH_FILE).lines().toList()).containsExactlyInAnyOrder("Commit 1", "Commit 2",
				"Commit 3", "Commit 4", "Commit 5", "Commit 6", "Commit 7", "Commit 8", "Commit 9", "Commit 10");
	}

	@Test
	void shouldAssignDistinctConsecutiveSequencesToConcurrentCommits() {
		postConcurrentCommits();

		assertThat(repository.findAll()).extracting(CommitLog::getSequenceNumber)
			.containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
	}

}
