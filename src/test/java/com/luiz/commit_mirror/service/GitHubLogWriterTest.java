package com.luiz.commit_mirror.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import com.luiz.commit_mirror.TestProperties;
import com.luiz.commit_mirror.config.AppProperties;
import com.luiz.commit_mirror.config.ResilienceConfig;
import com.luiz.commit_mirror.integration.GitHubConflictException;
import com.luiz.commit_mirror.integration.GitHubContentsGateway;
import com.luiz.commit_mirror.integration.GitHubPermanentException;
import com.luiz.commit_mirror.integration.GitHubTransientException;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;

class GitHubLogWriterTest {

	private static final Instant WHEN = Instant.parse("2026-10-21T15:00:00Z");

	private final GitHubContentsGateway gateway = mock(GitHubContentsGateway.class);

	private final AppProperties.Resilience resilience = TestProperties.resilience();

	private final CircuitBreaker circuitBreaker = ResilienceConfig.circuitBreaker(resilience);

	private final GitHubLogWriter writer = new GitHubLogWriter(gateway, ResilienceConfig.retry(resilience),
			circuitBreaker);

	private String append() {
		return writer.append("logs/2026-10.md", "Commit 1", "Commit 1", WHEN);
	}

	private void gatewayThrowsAlways(RuntimeException exception) {
		when(gateway.appendLine(anyString(), anyString(), anyString(), any())).thenThrow(exception);
	}

	@Test
	void shouldRetryOnConflict() {
		when(gateway.appendLine(anyString(), anyString(), anyString(), any()))
			.thenThrow(new GitHubConflictException("409"))
			.thenThrow(new GitHubConflictException("409"))
			.thenReturn("sha");

		assertThat(append()).isEqualTo("sha");
		verify(gateway, times(3)).appendLine(anyString(), anyString(), anyString(), any());
	}

	@Test
	void shouldRetryOnServerErrorWithBackoff() {
		when(gateway.appendLine(anyString(), anyString(), anyString(), any()))
			.thenThrow(new GitHubTransientException("503"))
			.thenReturn("sha");

		assertThat(append()).isEqualTo("sha");
		verify(gateway, times(2)).appendLine(anyString(), anyString(), anyString(), any());
	}

	@Test
	void shouldNotRetryOnUnauthorized() {
		gatewayThrowsAlways(new GitHubPermanentException("401"));

		assertThatThrownBy(this::append).isInstanceOf(GitHubPermanentException.class);
		verify(gateway, times(1)).appendLine(anyString(), anyString(), anyString(), any());
	}

	@Test
	void shouldStopAfterMaxAttempts() {
		gatewayThrowsAlways(new GitHubTransientException("503"));

		assertThatThrownBy(this::append).isInstanceOf(GitHubTransientException.class);
		verify(gateway, times(resilience.retryMaxAttempts())).appendLine(anyString(), anyString(), anyString(),
				any());
	}

	@Test
	void shouldOpenCircuitAfterFailureRateThreshold() {
		gatewayThrowsAlways(new GitHubTransientException("503"));

		assertThatThrownBy(this::append).isInstanceOf(GitHubTransientException.class);
		assertThatThrownBy(this::append).isInstanceOf(GitHubTransientException.class);

		assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
	}

	@Test
	void shouldFailFastWhenCircuitIsOpen() {
		gatewayThrowsAlways(new GitHubTransientException("503"));
		assertThatThrownBy(this::append).isInstanceOf(GitHubTransientException.class);
		assertThatThrownBy(this::append).isInstanceOf(GitHubTransientException.class);
		int callsWhenOpened = org.mockito.Mockito.mockingDetails(gateway).getInvocations().size();

		assertThatThrownBy(this::append).isInstanceOf(GitHubTransientException.class)
			.hasMessageContaining("circuit");

		assertThat(org.mockito.Mockito.mockingDetails(gateway).getInvocations()).hasSize(callsWhenOpened);
	}

	@Test
	void shouldNotCountConflictsAsFailures() {
		gatewayThrowsAlways(new GitHubConflictException("409"));

		for (int i = 0; i < 5; i++) {
			assertThatThrownBy(this::append).isInstanceOf(GitHubConflictException.class);
		}

		assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
	}

	@Test
	void shouldCloseAfterSuccessfulHalfOpenCalls() throws InterruptedException {
		AtomicBoolean healthy = new AtomicBoolean(false);
		when(gateway.appendLine(anyString(), anyString(), anyString(), any())).thenAnswer(invocation -> {
			if (healthy.get()) {
				return "sha";
			}
			throw new GitHubTransientException("503");
		});
		assertThatThrownBy(this::append).isInstanceOf(GitHubTransientException.class);
		assertThatThrownBy(this::append).isInstanceOf(GitHubTransientException.class);
		assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

		Thread.sleep(resilience.circuitOpenDuration().toMillis() + 30);
		healthy.set(true);
		for (int i = 0; i < resilience.circuitHalfOpenCalls(); i++) {
			assertThat(append()).isEqualTo("sha");
		}

		assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
	}

	@Test
	void shouldCreateRetryWithConfiguredAttempts() {
		Retry retry = ResilienceConfig.retry(resilience);

		assertThat(retry.getRetryConfig().getMaxAttempts()).isEqualTo(resilience.retryMaxAttempts());
	}

}
