package com.luiz.commit_mirror.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.luiz.commit_mirror.TestProperties;

class CommitDateResolverTest {

	private final CommitDateResolver saoPauloResolver = new CommitDateResolver(TestProperties.defaults());

	@Test
	void shouldResolveLocalDateInConfiguredZone() {
		assertThat(saoPauloResolver.resolve(Instant.parse("2026-10-21T15:00:00Z"))).isEqualTo(LocalDate.of(2026, 10, 21));
	}

	@Test
	void shouldKeepPreviousLocalDateWhenUtcHasAlreadyTurnedTheDay() {
		assertThat(saoPauloResolver.resolve(Instant.parse("2026-10-22T02:30:00Z"))).isEqualTo(LocalDate.of(2026, 10, 21));
	}

	@Test
	void shouldTurnTheLocalDateAtLocalMidnight() {
		assertThat(saoPauloResolver.resolve(Instant.parse("2026-10-22T03:00:00Z"))).isEqualTo(LocalDate.of(2026, 10, 22));
	}

	@Test
	void shouldUseAnotherConfiguredZone() {
		CommitDateResolver utcResolver = new CommitDateResolver(TestProperties.withTimeZone("UTC"));

		assertThat(utcResolver.resolve(Instant.parse("2026-10-22T02:30:00Z"))).isEqualTo(LocalDate.of(2026, 10, 22));
	}

	@Test
	void shouldRejectUnknownTimeZone() {
		assertThatThrownBy(() -> new CommitDateResolver(TestProperties.withTimeZone("Mars/Olympus")))
			.isInstanceOf(DateTimeException.class);
	}

}
