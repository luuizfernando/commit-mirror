package com.luiz.commit_mirror.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.luiz.commit_mirror.TestProperties;
import com.luiz.commit_mirror.config.AppProperties;

class LogFilePathResolverTest {

	@Test
	void shouldResolveMonthlyFilePath() {
		LogFilePathResolver resolver = new LogFilePathResolver(TestProperties.defaults());

		assertThat(resolver.resolve(LocalDate.of(2026, 10, 21))).isEqualTo("logs/2026-10.md");
	}

	@Test
	void shouldUseConfiguredDirectory() {
		AppProperties.GitHub github = new AppProperties.GitHub("t", "o", "r", "main", "http://gh", "commit-logs", "n",
				"e@x.com");
		AppProperties base = TestProperties.defaults();
		LogFilePathResolver resolver = new LogFilePathResolver(new AppProperties(base.telegram(), github,
				base.timeZone(), base.recovery(), base.resilience()));

		assertThat(resolver.resolve(LocalDate.of(2026, 1, 5))).isEqualTo("commit-logs/2026-01.md");
	}

	@Test
	void shouldUseCommitDateMonthAtMonthBoundary() {
		LogFilePathResolver resolver = new LogFilePathResolver(TestProperties.defaults());

		assertThat(resolver.resolve(LocalDate.of(2026, 10, 31))).isEqualTo("logs/2026-10.md");
		assertThat(resolver.resolve(LocalDate.of(2026, 11, 1))).isEqualTo("logs/2026-11.md");
	}

}
