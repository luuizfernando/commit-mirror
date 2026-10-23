package com.luiz.commit_mirror.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.luiz.commit_mirror.TestProperties;
import com.luiz.commit_mirror.config.AppProperties;
import com.luiz.commit_mirror.config.ResilienceConfig;
import com.luiz.commit_mirror.integration.GitHubConflictException;
import com.luiz.commit_mirror.integration.GitHubContentsGateway;
import com.luiz.commit_mirror.integration.GitHubPermanentException;
import com.luiz.commit_mirror.integration.GitHubTransientException;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

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
		when(gateway.appendLineIfAbsent(anyString(), anyString(), anyString(), any())).thenThrow(exception);
	}

	private void gatewayFailsTwiceWithConflictThenReturnsSha() {
		when(gateway.appendLineIfAbsent(anyString(), anyString(), anyString(), any()))
			.thenThrow(new GitHubConflictException("409"))
			.thenThrow(new GitHubConflictException("409"))
			.thenReturn("sha");
	}

	private void gatewayFailsOnceWithServerErrorThenReturnsSha() {
		when(gateway.appendLineIfAbsent(anyString(), anyString(), anyString(), any()))
			.thenThrow(new GitHubTransientException("503"))
			.thenReturn("sha");
	}

	private void verifyGatewayCalled(int times) {
		verify(gateway, times(times)).appendLineIfAbsent(anyString(), anyString(), anyString(), any());
	}

	private void tripCircuitWithServerErrors() {
		gatewayThrowsAlways(new GitHubTransientException("503"));
		catchThrowable(this::append);
		catchThrowable(this::append);
	}

	private void moveCircuitToHalfOpen() {
		circuitBreaker.transitionToOpenState();
		circuitBreaker.transitionToHalfOpenState();
	}

	@Test
	void shouldReturnTheShaFromTheGateway() {
		when(gateway.appendLineIfAbsent("logs/2026-10.md", "Commit 1", "Commit 1", WHEN)).thenReturn("sha");

		assertThat(append()).isEqualTo("sha");
	}

	@Test
	void shouldReturnShaWhenConflictsAreFollowedBySuccess() {
		gatewayFailsTwiceWithConflictThenReturnsSha();

		assertThat(append()).isEqualTo("sha");
	}

	@Test
	void shouldRepeatTheWholeAppendForEachConflict() {
		gatewayFailsTwiceWithConflictThenReturnsSha();

		append();

		verifyGatewayCalled(3);
	}

	@Test
	void shouldReturnShaWhenServerErrorIsFollowedBySuccess() {
		gatewayFailsOnceWithServerErrorThenReturnsSha();

		assertThat(append()).isEqualTo("sha");
	}

	@Test
	void shouldRepeatTheWholeAppendAfterServerError() {
		gatewayFailsOnceWithServerErrorThenReturnsSha();

		append();

		verifyGatewayCalled(2);
	}

	@Test
	void shouldPropagatePermanentFailure() {
		gatewayThrowsAlways(new GitHubPermanentException("401"));

		assertThatThrownBy(this::append).isInstanceOf(GitHubPermanentException.class);
	}

	@Test
	void shouldNotRetryPermanentFailure() {
		gatewayThrowsAlways(new GitHubPermanentException("401"));

		catchThrowable(this::append);

		verifyGatewayCalled(1);
	}

	@Test
	void shouldPropagateTransientFailureWhenRetriesAreExhausted() {
		gatewayThrowsAlways(new GitHubTransientException("503"));

		assertThatThrownBy(this::append).isInstanceOf(GitHubTransientException.class);
	}

	@Test
	void shouldStopRetryingAfterMaxAttempts() {
		gatewayThrowsAlways(new GitHubTransientException("503"));

		catchThrowable(this::append);

		verifyGatewayCalled(resilience.retryMaxAttempts());
	}

	@Test
	void shouldPropagateConflictWhenRetriesAreExhausted() {
		gatewayThrowsAlways(new GitHubConflictException("409"));

		assertThatThrownBy(this::append).isInstanceOf(GitHubConflictException.class);
	}

	@Test
	void shouldOpenCircuitAfterFailureRateThreshold() {
		tripCircuitWithServerErrors();

		assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
	}

	@Test
	void shouldFailFastWithTransientCircuitErrorWhenCircuitIsOpen() {
		circuitBreaker.transitionToOpenState();

		assertThatThrownBy(this::append).isInstanceOf(GitHubTransientException.class).hasMessageContaining("circuit");
	}

	@Test
	void shouldNotCallGatewayWhenCircuitIsOpen() {
		circuitBreaker.transitionToOpenState();

		catchThrowable(this::append);

		verifyNoInteractions(gateway);
	}

	@Test
	void shouldNotCountConflictsAsFailures() {
		gatewayThrowsAlways(new GitHubConflictException("409"));

		for (int i = 0; i < 5; i++) {
			catchThrowable(this::append);
		}

		assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
	}

	@Test
	void shouldNotCountPermanentFailuresAsFailures() {
		gatewayThrowsAlways(new GitHubPermanentException("401"));

		for (int i = 0; i < resilience.circuitMinimumCalls(); i++) {
			catchThrowable(this::append);
		}

		assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
	}

	@Test
	void shouldCloseCircuitAfterSuccessfulHalfOpenCalls() {
		moveCircuitToHalfOpen();
		when(gateway.appendLineIfAbsent(anyString(), anyString(), anyString(), any())).thenReturn("sha");

		for (int i = 0; i < resilience.circuitHalfOpenCalls(); i++) {
			append();
		}

		assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
	}

	@Test
	void shouldReopenCircuitWhenHalfOpenCallsFail() {
		moveCircuitToHalfOpen();
		gatewayThrowsAlways(new GitHubTransientException("503"));

		catchThrowable(this::append);

		assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
	}

}
