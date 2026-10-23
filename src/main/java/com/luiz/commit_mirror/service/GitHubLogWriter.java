package com.luiz.commit_mirror.service;

import java.time.Instant;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;

import com.luiz.commit_mirror.integration.GitHubContentsGateway;
import com.luiz.commit_mirror.integration.GitHubTransientException;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;

@Service
public class GitHubLogWriter {

	private final GitHubContentsGateway gateway;

	private final Retry retry;

	private final CircuitBreaker circuitBreaker;

	public GitHubLogWriter(GitHubContentsGateway gateway, Retry retry, CircuitBreaker circuitBreaker) {
		this.gateway = gateway;
		this.retry = retry;
		this.circuitBreaker = circuitBreaker;
	}

	public String append(String path, String line, String commitMessage, Instant timestamp) {
		Supplier<String> call = () -> gateway.appendLineIfAbsent(path, line, commitMessage, timestamp);
		Supplier<String> resilient = Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(circuitBreaker, call));
		try {
			return resilient.get();
		}
		catch (CallNotPermittedException ex) {
			throw new GitHubTransientException("GitHub circuit breaker is open");
		}
	}

}
