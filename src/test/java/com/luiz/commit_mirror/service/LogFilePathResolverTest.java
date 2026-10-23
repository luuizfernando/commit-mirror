package com.luiz.commit_mirror.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.luiz.commit_mirror.TestProperties;
import com.luiz.commit_mirror.config.AppProperties;

class LogFilePathResolverTest {

	private final LogFilePathResolver resolver = new LogFilePathResolver(TestProperties.defaults());

	@Test
	void shouldResolveMonthlyFilePath() {
		assertThat(resolver.resolve(LocalDate.of(2026, 10, 21))).isEqualTo("logs/2026-10.md");
	}

	@Test
	void shouldUseConfiguredDirectory() {
		AppProperties.GitHub github = new AppProperties.GitHub("t", "o", "r", "main", "http://gh", "commit-logs", "n",
				"e@x.com");
		LogFilePathResolver customResolver = new LogFilePathResolver(TestProperties.withGitHub(github));

		assertThat(customResolver.resolve(LocalDate.of(2026, 10, 21))).isEqualTo("commit-logs/2026-10.md");
	}

	@Test
	void shouldZeroPadSingleDigitMonth() {
		assertThat(resolver.resolve(LocalDate.of(2026, 1, 5))).isEqualTo("logs/2026-01.md");
	}

	@Test
	void shouldKeepLastDayOfMonthInThatMonthFile() {
		assertThat(resolver.resolve(LocalDate.of(2026, 10, 31))).isEqualTo("logs/2026-10.md");
	}

	@Test
	void shouldMoveFirstDayOfMonthToTheNewMonthFile() {
		assertThat(resolver.resolve(LocalDate.of(2026, 11, 1))).isEqualTo("logs/2026-11.md");
	}

	@Test
	void shouldMoveFirstDayOfYearToTheNewYearFile() {
		assertThat(resolver.resolve(LocalDate.of(2027, 1, 1))).isEqualTo("logs/2027-01.md");
	}

}
