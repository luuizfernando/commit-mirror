package com.luiz.commit_mirror;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

	private String monthFile() {
		CommitLog first = repository.findAll().get(0);
		return "logs/" + first.getCommitDate().toString().substring(0, 7) + ".md";
	}

	@Test
	void shouldCreateMonthFileOnFirstCommit() {
		int status = postCommit(1L, TYPED_TEXT);

		assertThat(status).isEqualTo(200);
		await().atMost(timeout()).untilAsserted(() -> assertThat(GITHUB.commits()).hasSize(1));
		assertThat(GITHUB.fileContent(monthFile())).isEqualTo("Commit 1\n");
		assertThat(GITHUB.commits().get(0).message()).isEqualTo("Commit 1");
		await().atMost(timeout())
			.untilAsserted(() -> assertThat(TELEGRAM.sent()).containsExactly(new Sent(CHAT_ID, "Commit 1 registrado.")));
	}

	@Test
	void shouldAppendOnSecondCommit() {
		postCommit(1L, "one");
		await().atMost(timeout()).untilAsserted(() -> assertThat(GITHUB.commits()).hasSize(1));

		postCommit(2L, "two");
		await().atMost(timeout()).untilAsserted(() -> assertThat(GITHUB.commits()).hasSize(2));

		assertThat(GITHUB.fileContent(monthFile())).isEqualTo("Commit 1\nCommit 2\n");
		assertThat(GITHUB.commits()).extracting(Commit::message).containsExactly("Commit 1", "Commit 2");
	}

	@Test
	void shouldStoreSuccessWithShaAndSendAuthorAndExplicitDates() {
		postCommit(1L, TYPED_TEXT);
		await().atMost(timeout())
			.untilAsserted(() -> assertThat(repository.findAll()).extracting(CommitLog::getStatus)
				.containsExactly(CommitStatus.SUCCESS));

		CommitLog saved = repository.findAll().get(0);
		Commit commit = GITHUB.commits().get(0);
		String expectedDate = saved.getCreatedAt().truncatedTo(ChronoUnit.SECONDS).toString();
		assertThat(saved.getGithubCommitSha()).isNotBlank();
		assertThat(commit.authorName()).isEqualTo("Test Author");
		assertThat(commit.authorEmail()).isEqualTo("author@example.com");
		assertThat(commit.authorDate()).isEqualTo(expectedDate);
		assertThat(commit.committerDate()).isEqualTo(expectedDate);
		assertThat(commit.branch()).isEqualTo("main");
		assertThat(commit.authorization()).isEqualTo("Bearer test-github-token");
		assertThat(Instant.parse(commit.authorDate())).isBeforeOrEqualTo(Instant.now());
	}

	@Test
	void shouldNeverSendTypedTextToGitHub() {
		postCommit(1L, TYPED_TEXT);
		await().atMost(timeout()).untilAsserted(() -> assertThat(GITHUB.commits()).hasSize(1));

		assertThat(GITHUB.commits()).noneMatch(commit -> commit.rawBody().contains(TYPED_TEXT));
		assertThat(GITHUB.fileContent(monthFile())).doesNotContain(TYPED_TEXT);
		assertThat(repository.findAll().get(0).getMessage()).isEqualTo(TYPED_TEXT);
	}

	@Test
	void shouldIgnoreRedeliveredUpdate() {
		postCommit(1L, "once");
		await().atMost(timeout()).untilAsserted(() -> assertThat(GITHUB.commits()).hasSize(1));

		int status = postCommit(1L, "once");

		assertThat(status).isEqualTo(200);
		assertThat(repository.count()).isEqualTo(1L);
		assertThat(GITHUB.commits()).hasSize(1);
	}

	@Test
	void shouldWriteTenConcurrentCommitsWithoutLossOrDuplication() {
		ExecutorService pool = Executors.newFixedThreadPool(10);
		try {
			List<CompletableFuture<Integer>> posts = IntStream.rangeClosed(1, 10)
				.mapToObj(i -> CompletableFuture.supplyAsync(() -> postCommit(i, "concurrent"), pool))
				.toList();
			posts.forEach(CompletableFuture::join);

			await().atMost(timeout()).untilAsserted(() -> assertThat(GITHUB.commits()).hasSize(10));
		}
		finally {
			pool.shutdownNow();
		}

		List<String> lines = GITHUB.fileContent(monthFile()).lines().toList();
		assertThat(lines).hasSize(10)
			.containsExactlyInAnyOrder("Commit 1", "Commit 2", "Commit 3", "Commit 4", "Commit 5", "Commit 6",
					"Commit 7", "Commit 8", "Commit 9", "Commit 10");
		assertThat(repository.findAll()).extracting(CommitLog::getSequenceNumber)
			.containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
	}

}
