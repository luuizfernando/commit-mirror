package com.luiz.commit_mirror;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.system.CapturedOutput;

import com.luiz.commit_mirror.support.FakeTelegramServer.Sent;

class WebhookFlowIT extends AbstractFlowIT {

	private static final String TYPED_TEXT = "my-very-private-work-description";

	@Test
	void shouldRejectWebhookWithWrongSecretWithoutTouchingAnything() {
		int status = postUpdate("wrong-secret", 1L, ALLOWED_USER, "/commit " + TYPED_TEXT);

		assertThat(status).isEqualTo(403);
		assertThat(repository.count()).isZero();
		assertThat(TELEGRAM.sent()).isEmpty();
		assertThat(GITHUB.commits()).isEmpty();
	}

	@Test
	void shouldIgnoreUserOutsideAllowlistSilently() {
		int status = postUpdate(SECRET, 1L, 7L, "/commit " + TYPED_TEXT);

		assertThat(status).isEqualTo(200);
		assertThat(repository.count()).isZero();
		assertThat(TELEGRAM.sent()).isEmpty();
	}

	@Test
	void shouldReplyUsageWhenCommitHasNoMessage() {
		int status = postUpdate(SECRET, 1L, ALLOWED_USER, "/commit");

		assertThat(status).isEqualTo(200);
		assertThat(TELEGRAM.sent()).containsExactly(new Sent(CHAT_ID, "Uso: /commit <mensagem>"));
		assertThat(repository.count()).isZero();
	}

	@Test
	void shouldReplyWhenCommandIsUnknown() {
		postUpdate(SECRET, 1L, ALLOWED_USER, "/nope");

		assertThat(TELEGRAM.sent()).containsExactly(new Sent(CHAT_ID, "Comando desconhecido: /nope"));
	}

	@Test
	void shouldNeverLogTypedTextAcrossTheWholeFlow(CapturedOutput output) {
		postCommit(1L, TYPED_TEXT);
		await().atMost(timeout()).untilAsserted(() -> assertThat(GITHUB.commits()).hasSize(1));

		postUpdate("wrong-secret", 2L, ALLOWED_USER, "/commit " + TYPED_TEXT);
		postUpdate(SECRET, 3L, 7L, "/commit " + TYPED_TEXT);
		postCommit(1L, TYPED_TEXT);

		GITHUB.failWith(503);
		postCommit(4L, TYPED_TEXT);
		await().atMost(timeout())
			.untilAsserted(() -> assertThat(TELEGRAM.sent()).anyMatch(sent -> sent.text().contains("Commit 2")));

		GITHUB.recover();
		githubCircuitBreaker.reset();
		TELEGRAM.failing(true);
		postCommit(5L, TYPED_TEXT);
		await().atMost(timeout()).untilAsserted(() -> assertThat(GITHUB.commits()).hasSize(2));

		assertThat(output.getAll()).doesNotContain(TYPED_TEXT);
	}

}
