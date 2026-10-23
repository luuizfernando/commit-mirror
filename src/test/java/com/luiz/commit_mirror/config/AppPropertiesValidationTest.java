package com.luiz.commit_mirror.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AppPropertiesValidationTest {

	private static final String[] REQUIRED = { "app.telegram.bot-token=token", "app.telegram.webhook-secret=secret",
			"app.telegram.allowed-user-id=42", "app.github.token=t", "app.github.owner=o", "app.github.repo=r",
			"app.github.author-name=n", "app.github.author-email=e@x.com" };

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(PropertiesConfig.class);

	private ApplicationContextRunner withRequired() {
		return runner.withPropertyValues(REQUIRED);
	}

	private AppProperties loadedProperties() {
		AppProperties[] loaded = new AppProperties[1];
		withRequired().run(context -> loaded[0] = context.getBean(AppProperties.class));
		return loaded[0];
	}

	/** All required properties, with {@code key} left blank. */
	private ApplicationContextRunner withBlank(String key) {
		String[] values = Arrays.stream(REQUIRED).map(entry -> entry.startsWith(key + "=") ? key + "=" : entry)
			.toArray(String[]::new);
		return runner.withPropertyValues(values);
	}

	// happy path

	@Test
	void shouldStartWhenAllRequiredPropertiesAreSet() {
		withRequired().run(context -> assertThat(context).hasNotFailed());
	}

	// required secrets and identities

	@ParameterizedTest
	@ValueSource(strings = { "app.telegram.bot-token", "app.telegram.webhook-secret", "app.telegram.allowed-user-id",
			"app.github.token", "app.github.owner", "app.github.repo", "app.github.author-name",
			"app.github.author-email" })
	void shouldFailStartupWhenRequiredPropertyIsBlank(String key) {
		withBlank(key).run(context -> assertThat(context).hasFailed());
	}

	@Test
	void shouldFailStartupWhenRecoveryMaxAttemptsIsZero() {
		withRequired().withPropertyValues("app.recovery.max-attempts=0")
			.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void shouldFailStartupWhenRetryMaxAttemptsIsNegative() {
		withRequired().withPropertyValues("app.resilience.retry-max-attempts=-1")
			.run(context -> assertThat(context).hasFailed());
	}

	// defaults

	@Test
	void shouldDefaultTelegramBaseUrlToTheOfficialApi() {
		assertThat(loadedProperties().telegram().baseUrl()).isEqualTo("https://api.telegram.org");
	}

	@Test
	void shouldDefaultGitHubBaseUrlToTheOfficialApi() {
		assertThat(loadedProperties().github().baseUrl()).isEqualTo("https://api.github.com");
	}

	@Test
	void shouldDefaultBranchToMain() {
		assertThat(loadedProperties().github().branch()).isEqualTo("main");
	}

	@Test
	void shouldDefaultLogsDirectoryToCommitLogs() {
		assertThat(loadedProperties().github().logsDirectory()).isEqualTo("commit-logs");
	}

	@Test
	void shouldDefaultTimeZoneToSaoPaulo() {
		assertThat(loadedProperties().timeZone()).isEqualTo("America/Sao_Paulo");
	}

	@Test
	void shouldEnableRecoveryByDefault() {
		assertThat(loadedProperties().recovery().enabled()).isTrue();
	}

	@EnableConfigurationProperties(AppProperties.class)
	static class PropertiesConfig {

	}

}
