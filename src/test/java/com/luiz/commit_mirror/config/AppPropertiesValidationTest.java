package com.luiz.commit_mirror.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AppPropertiesValidationTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(PropertiesConfig.class);

	@Test
	void shouldFailStartupWhenWebhookSecretIsBlank() {
		runner.withPropertyValues("app.telegram.bot-token=token", "app.telegram.webhook-secret=",
				"app.telegram.allowed-user-id=42", "app.github.token=t", "app.github.owner=o", "app.github.repo=r", "app.github.author-name=n", "app.github.author-email=e@x.com")
				.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void shouldStartWhenAllRequiredTelegramPropertiesAreSet() {
		runner.withPropertyValues("app.telegram.bot-token=token", "app.telegram.webhook-secret=secret",
				"app.telegram.allowed-user-id=42", "app.github.token=t", "app.github.owner=o", "app.github.repo=r", "app.github.author-name=n", "app.github.author-email=e@x.com")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context.getBean(AppProperties.class).telegram().baseUrl())
							.isEqualTo("https://api.telegram.org");
				});
	}

	@EnableConfigurationProperties(AppProperties.class)
	static class PropertiesConfig {

	}

}
