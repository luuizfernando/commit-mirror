package com.luiz.commit_mirror.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.luiz.commit_mirror.integration.GitHubConflictException;
import com.luiz.commit_mirror.integration.GitHubPermanentException;
import com.luiz.commit_mirror.integration.GitHubTransientException;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class ResilienceConfig {

	private static final String NAME = "github";

	@Bean
	Retry githubRetry(AppProperties properties) {
		return retry(properties.resilience());
	}

	@Bean
	CircuitBreaker githubCircuitBreaker(AppProperties properties) {
		return circuitBreaker(properties.resilience());
	}

	public static Retry retry(AppProperties.Resilience settings) {
		RetryConfig config = RetryConfig.custom()
				.maxAttempts(settings.retryMaxAttempts())
				.intervalFunction(IntervalFunction.ofExponentialRandomBackoff(settings.retryInitialBackoff(), 2.0, 0.5))
				.retryExceptions(GitHubConflictException.class, GitHubTransientException.class)
				.build();
		return Retry.of(NAME, config);
	}

	public static CircuitBreaker circuitBreaker(AppProperties.Resilience settings) {
		CircuitBreakerConfig config = CircuitBreakerConfig.custom()
				.slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
				.slidingWindowSize(settings.circuitWindowSize())
				.minimumNumberOfCalls(settings.circuitMinimumCalls())
				.failureRateThreshold(settings.circuitFailureRatePercent())
				.waitDurationInOpenState(settings.circuitOpenDuration())
				.permittedNumberOfCallsInHalfOpenState(settings.circuitHalfOpenCalls())
				.recordExceptions(GitHubTransientException.class)
				.ignoreExceptions(GitHubConflictException.class, GitHubPermanentException.class)
				.build();
		return CircuitBreaker.of(NAME, config);
	}

}
