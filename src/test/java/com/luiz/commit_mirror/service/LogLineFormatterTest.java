package com.luiz.commit_mirror.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LogLineFormatterTest {

	private final LogLineFormatter formatter = new LogLineFormatter();

	@Test
	void shouldFormatLineAsCommitN() {
		assertThat(formatter.format(42L)).isEqualTo("Commit 42");
	}

	@Test
	void shouldNotIncludeDate() {
		assertThat(formatter.format(7L)).doesNotContainPattern("\\d{4}-\\d{2}|:\\d{2}");
	}

}
