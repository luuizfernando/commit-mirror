package com.luiz.commit_mirror.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.luiz.commit_mirror.TestProperties;
import com.luiz.commit_mirror.config.AppProperties;
import com.luiz.commit_mirror.exception.InvalidWebhookSecretException;

class WebhookSecretValidatorTest {

	private final WebhookSecretValidator validator = new WebhookSecretValidator(
			TestProperties.withTelegram(new AppProperties.Telegram("token", "expected-secret", 42L, "http://telegram")));

	@Test
	void shouldAcceptWhenSecretMatches() {
		assertThatCode(() -> validator.validate("expected-secret")).doesNotThrowAnyException();
	}

	@Test
	void shouldRejectWhenSecretDiffers() {
		assertThatThrownBy(() -> validator.validate("other-secret-x"))
			.isInstanceOf(InvalidWebhookSecretException.class);
	}

	@Test
	void shouldRejectWhenHeaderIsMissing() {
		assertThatThrownBy(() -> validator.validate(null)).isInstanceOf(InvalidWebhookSecretException.class);
	}

	@Test
	void shouldRejectWhenSecretHasDifferentLength() {
		assertThatThrownBy(() -> validator.validate("short")).isInstanceOf(InvalidWebhookSecretException.class);
	}

}
