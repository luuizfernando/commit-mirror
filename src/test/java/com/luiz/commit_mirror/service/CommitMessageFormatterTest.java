package com.luiz.commit_mirror.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CommitMessageFormatterTest {

	private final CommitMessageFormatter formatter = new CommitMessageFormatter();

	@Test
	void shouldFormatMessageAsCommitN() {
		assertThat(formatter.format(42L)).isEqualTo("Commit 42");
	}

	@Test
	void shouldNotIncludeDateOrTimeInMessage() {
		assertThat(formatter.format(1L)).doesNotContainPattern("\\d{4}-\\d{2}|:\\d{2}");
	}

}
