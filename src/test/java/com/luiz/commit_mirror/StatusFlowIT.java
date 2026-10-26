package com.luiz.commit_mirror;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.luiz.commit_mirror.domain.CommitStatus;
import com.luiz.commit_mirror.support.FakeTelegramServer.Sent;

class StatusFlowIT extends AbstractFlowIT {

	private static final long OTHER_USER = 7L;

	private static final long STATUS_UPDATE_ID = 99L;

	private int postStatus(long userId) {
		return postUpdate(SECRET, STATUS_UPDATE_ID, userId, "/status");
	}

	private void mirrorTwoCommits() {
		postCommit(1L, "one");
		awaitGitHubCommits(1);
		postCommit(2L, "two");
		awaitGitHubCommits(2);
		awaitTelegramMessages(2);
		awaitEveryRecordStatus(CommitStatus.SUCCESS);
	}

	private void awaitEveryRecordStatus(CommitStatus status) {
		await().atMost(timeout())
			.untilAsserted(() -> assertThat(repository.findAll()).allMatch(log -> log.getStatus() == status));
	}

	private Sent lastSentMessage() {
		List<Sent> sent = TELEGRAM.sent();
		return sent.get(sent.size() - 1);
	}

	@Test
	void shouldReplyNoCommitsWhenNothingWasMirrored() {
		postStatus(ALLOWED_USER);
		awaitTelegramMessages(1);

		assertThat(lastSentMessage()).isEqualTo(new Sent(CHAT_ID, "Nenhum commit salvo ainda."));
	}

	@Test
	void shouldReplyTheCountOfMirroredCommits() {
		mirrorTwoCommits();

		postStatus(ALLOWED_USER);
		awaitTelegramMessages(3);

		assertThat(lastSentMessage()).isEqualTo(new Sent(CHAT_ID, "Você já salvou 2 commits com o Commit Mirror."));
	}

	@Test
	void shouldNotReplyStatusToUserOutsideAllowlist() {
		postStatus(OTHER_USER);

		assertThat(TELEGRAM.sent()).isEmpty();
	}

}
