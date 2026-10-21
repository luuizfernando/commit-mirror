package com.luiz.commit_mirror.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Validated
@ConfigurationProperties("app")
public record AppProperties(@Valid @NotNull Telegram telegram, @Valid @NotNull GitHub github,
		@NotBlank @DefaultValue("America/Sao_Paulo") String timeZone, @Valid @DefaultValue Recovery recovery,
		@Valid @DefaultValue Resilience resilience) {

	public record Telegram(@NotBlank String botToken, @NotBlank String webhookSecret, @NotNull Long allowedUserId,
			@NotBlank @DefaultValue("https://api.telegram.org") String baseUrl) {
	}

	public record GitHub(@NotBlank String token, @NotBlank String owner, @NotBlank String repo,
			@NotBlank @DefaultValue("main") String branch,
			@NotBlank @DefaultValue("https://api.github.com") String baseUrl,
			@NotBlank @DefaultValue("commit-logs") String logsDirectory, @NotBlank String authorName,
			@NotBlank String authorEmail) {
	}

	public record Recovery(@DefaultValue("true") boolean enabled, @DefaultValue("60s") Duration interval,
			@DefaultValue("5m") Duration minAge, @Positive @DefaultValue("3") int maxAttempts) {
	}

	public record Resilience(@Positive @DefaultValue("4") int retryMaxAttempts,
			@DefaultValue("500ms") Duration retryInitialBackoff,
			@Positive @DefaultValue("10") int circuitWindowSize, @Positive @DefaultValue("5") int circuitMinimumCalls,
			@Positive @DefaultValue("50") int circuitFailureRatePercent,
			@DefaultValue("30s") Duration circuitOpenDuration,
			@Positive @DefaultValue("2") int circuitHalfOpenCalls,
			@Positive @DefaultValue("20") int sequenceMaxRetries) {
	}

}
