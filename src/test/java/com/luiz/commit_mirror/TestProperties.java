package com.luiz.commit_mirror;

import java.time.Duration;

import com.luiz.commit_mirror.config.AppProperties;

public final class TestProperties {

	private TestProperties() {
	}

	public static AppProperties defaults() {
		return withTelegram(new AppProperties.Telegram("token", "secret", 42L, "http://telegram"));
	}

	public static AppProperties withTelegram(AppProperties.Telegram telegram) {
		return new AppProperties(telegram, github(), "America/Sao_Paulo", recovery(), resilience());
	}

	public static AppProperties.GitHub github() {
		return new AppProperties.GitHub("gh-token", "owner", "repo", "main", "http://github", "logs", "Author Name",
				"author@example.com");
	}

	public static AppProperties.Recovery recovery() {
		return new AppProperties.Recovery(true, Duration.ofSeconds(60), Duration.ofMinutes(5), 3);
	}

	public static AppProperties.Resilience resilience() {
		return new AppProperties.Resilience(3, Duration.ofMillis(1), 10, 5, 50, Duration.ofMillis(50), 2, 3);
	}

}
