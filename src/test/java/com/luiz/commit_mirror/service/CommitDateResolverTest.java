package com.luiz.commit_mirror.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.luiz.commit_mirror.TestProperties;

class CommitDateResolverTest {

	private final CommitDateResolver resolver = new CommitDateResolver(TestProperties.defaults());

	@Test
	void shouldResolveLocalDateInConfiguredZone() {
		assertThat(resolver.resolve(Instant.parse("2026-10-21T15:00:00Z"))).isEqualTo(LocalDate.of(2026, 10, 21));
	}

	@Test
	void shouldKeepLocalDateNearMidnightUtcBoundary() {
		// 23:30 in America/Sao_Paulo is already 02:30Z of the next day
		assertThat(resolver.resolve(Instant.parse("2026-10-22T02:30:00Z"))).isEqualTo(LocalDate.of(2026, 10, 21));
		assertThat(resolver.resolve(Instant.parse("2026-10-22T03:00:00Z"))).isEqualTo(LocalDate.of(2026, 10, 22));
	}

}
