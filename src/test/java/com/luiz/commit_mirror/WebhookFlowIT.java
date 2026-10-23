package com.luiz.commit_mirror;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.system.CapturedOutput;

import com.luiz.commit_mirror.support.FakeTelegramServer.Sent;

class WebhookFlowIT extends AbstractFlowIT {

	private static final String TYPED_TEXT = "my-very-private-work-description";

	private static final String WRONG_SECRET = "wrong-secret";

	private static final long OTHER_USER = 7L;

	private int postWithWrongSecret() {
		return postUpdate(WRONG_SECRET, 1L, ALLOWED_USER, "/commit " + TYPED_TEXT);
	}

	private int postFromUserOutsideAllowlist() {
		return postUpdate(SECRET, 1L, OTHER_USER, "/commit " + TYPED_TEXT);
	}

	private int postCommitWithoutMessage() {
		return postUpdate(SECRET, 1L, ALLOWED_USER, "/commit");
	}

	@Test
	void shouldReturn403WhenSecretIsWrong() {
		assertThat(postWithWrongSecret()).isEqualTo(403);
	}

	@Test
	void shouldNotStoreAnythingWhenSecretIsWrong() {
		postWithWrongSecret();

		assertThat(repository.count()).isZero();
	}

	@Test
	void shouldNotReplyWhenSecretIsWrong() {
		postWithWrongSecret();

		assertThat(TELEGRAM.sent()).isEmpty();
	}

	@Test
	void shouldNotCallGitHubWhenSecretIsWrong() {
		postWithWrongSecret();

		assertThat(GITHUB.commits()).isEmpty();
	}

	@Test
	void shouldReturn200ToUserOutsideAllowlist() {
		assertThat(postFromUserOutsideAllowlist()).isEqualTo(200);
	}

	@Test
	void shouldNotStoreAnythingForUserOutsideAllowlist() {
		postFromUserOutsideAllowlist();

		assertThat(repository.count()).isZero();
	}

	@Test
	void shouldNotReplyToUserOutsideAllowlist() {
		postFromUserOutsideAllowlist();

		assertThat(TELEGRAM.sent()).isEmpty();
	}

	@Test
	void shouldReturn200WhenCommitHasNoMessage() {
		assertThat(postCommitWithoutMessage()).isEqualTo(200);
	}

	@Test
	void shouldReplyUsageWhenCommitHasNoMessage() {
		postCommitWithoutMessage();

		assertThat(TELEGRAM.sent()).containsExactly(new Sent(CHAT_ID, "Uso: /commit <mensagem>"));
	}

	@Test
	void shouldNotStoreCommitWithoutMessage() {
		postCommitWithoutMessage();

		assertThat(repository.count()).isZero();
	}

	@Test
	void shouldNotStoreCommitWhenTextIsOnlyASlash() {
		postUpdate(SECRET, 1L, ALLOWED_USER, "/");

		assertThat(repository.count()).isZero();
	}

	@Test
	void shouldNotStoreCommitWhenCommandIsCapitalized() {
		postUpdate(SECRET, 1L, ALLOWED_USER, "/Commit " + TYPED_TEXT);

		assertThat(repository.count()).isZero();
	}

	@Test
	void shouldReplyWhenCommandIsUnknown() {
		postUpdate(SECRET, 1L, ALLOWED_USER, "/nope");

		assertThat(TELEGRAM.sent()).containsExactly(new Sent(CHAT_ID, "Comando desconhecido: /nope"));
	}

	@Test
	void shouldNeverLogTypedTextOnSuccessfulCommit(CapturedOutput output) {
		postCommit(1L, TYPED_TEXT);
		awaitGitHubCommits(1);

		assertThat(output.getAll()).doesNotContain(TYPED_TEXT);
	}

	@Test
	void shouldNeverLogTypedTextWhenSecretIsWrong(CapturedOutput output) {
		postWithWrongSecret();

		assertThat(output.getAll()).doesNotContain(TYPED_TEXT);
	}

	@Test
	void shouldNeverLogTypedTextForUserOutsideAllowlist(CapturedOutput output) {
		postFromUserOutsideAllowlist();

		assertThat(output.getAll()).doesNotContain(TYPED_TEXT);
	}

	@Test
	void shouldNeverLogTypedTextForRedeliveredUpdate(CapturedOutput output) {
		postCommit(1L, TYPED_TEXT);
		awaitGitHubCommits(1);

		postCommit(1L, TYPED_TEXT);

		assertThat(output.getAll()).doesNotContain(TYPED_TEXT);
	}

	@Test
	void shouldNeverLogTypedTextWhenGitHubFails(CapturedOutput output) {
		GITHUB.failWith(503);

		postCommit(1L, TYPED_TEXT);
		awaitTelegramMessages(1);

		assertThat(output.getAll()).doesNotContain(TYPED_TEXT);
	}

	@Test
	void shouldNeverLogTypedTextWhenTelegramFails(CapturedOutput output) {
		TELEGRAM.failing(true);

		postCommit(1L, TYPED_TEXT);
		awaitGitHubCommits(1);
		await().atMost(timeout()).untilAsserted(() -> assertThat(output.getAll()).contains("Could not notify the user"));

		assertThat(output.getAll()).doesNotContain(TYPED_TEXT);
	}

}
