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
	void shouldFormatTheFirstSequence() {
		assertThat(formatter.format(1L)).isEqualTo("Commit 1");
	}

	@Test
	void shouldFormatTheMaximumSequence() {
		assertThat(formatter.format(Long.MAX_VALUE)).isEqualTo("Commit 9223372036854775807");
	}

	@Test
	void shouldNotIncludeDateOrTimeInMessage() {
		assertThat(formatter.format(1L)).doesNotContainPattern("\\d{4}-\\d{2}|:\\d{2}");
	}

}
