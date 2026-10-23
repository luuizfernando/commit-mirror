package com.luiz.commit_mirror.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.luiz.commit_mirror.TestProperties;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

class ResilienceConfigTest {

	private final AppProperties.Resilience resilience = TestProperties.resilience();

	@Test
	void shouldCreateRetryWithConfiguredAttempts() {
		assertThat(ResilienceConfig.retry(resilience).getRetryConfig().getMaxAttempts())
			.isEqualTo(resilience.retryMaxAttempts());
	}

	@Test
	void shouldCreateCircuitBreakerWithConfiguredOpenStateDuration() {
		CircuitBreaker circuitBreaker = ResilienceConfig.circuitBreaker(resilience);

		assertThat(circuitBreaker.getCircuitBreakerConfig().getWaitIntervalFunctionInOpenState().apply(1))
			.isEqualTo(resilience.circuitOpenDuration().toMillis());
	}

	@Test
	void shouldCreateCircuitBreakerWithConfiguredHalfOpenCalls() {
		CircuitBreaker circuitBreaker = ResilienceConfig.circuitBreaker(resilience);

		assertThat(circuitBreaker.getCircuitBreakerConfig().getPermittedNumberOfCallsInHalfOpenState())
			.isEqualTo(resilience.circuitHalfOpenCalls());
	}

}
