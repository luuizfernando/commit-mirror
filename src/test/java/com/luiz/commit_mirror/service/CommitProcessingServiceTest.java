package com.luiz.commit_mirror.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import com.luiz.commit_mirror.TestProperties;
import com.luiz.commit_mirror.domain.CommitLog;
import com.luiz.commit_mirror.domain.CommitStatus;
import com.luiz.commit_mirror.integration.GitHubPermanentException;
import com.luiz.commit_mirror.integration.GitHubTransientException;
import com.luiz.commit_mirror.integration.TelegramApiException;
import com.luiz.commit_mirror.integration.TelegramClient;
import com.luiz.commit_mirror.repository.CommitLogRepository;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class CommitProcessingServiceTest {

	private static final Instant CREATED = Instant.parse("2026-10-21T15:00:00Z");

	private static final String TYPED_TEXT = "private-typed-text";

	private final CommitLogRepository repository = mock(CommitLogRepository.class);

	private final GitHubLogWriter writer = mock(GitHubLogWriter.class);

	private final TelegramClient telegramClient = mock(TelegramClient.class);

	private final Clock clock = Clock.fixed(Instant.parse("2026-10-21T16:00:00Z"), ZoneOffset.UTC);

	private final CommitProcessingService service = new CommitProcessingService(repository, writer,
			new CommitMessageFormatter(), new LogLineFormatter(), new LogFilePathResolver(TestProperties.defaults()),
			telegramClient, clock, TestProperties.defaults());

	private final Logger logger = (Logger) LoggerFactory.getLogger(CommitProcessingService.class);

	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

	@BeforeEach
	void attachAppender() {
		appender.start();
		logger.addAppender(appender);
	}

	@AfterEach
	void detachAppender() {
		logger.detachAppender(appender);
	}

	private CommitLog pendingLog() {
		return CommitLog.create(10L, 100L, TYPED_TEXT, LocalDate.of(2026, 10, 21), 7L, CREATED);
	}

	private CommitLog logWithFailures(int failures) {
		CommitLog log = pendingLog();
		for (int i = 0; i < failures; i++) {
			log.markFailed(CREATED);
			if (i < failures - 1) {
				log.reopen(CREATED);
			}
		}
		return log;
	}

	private void found(CommitLog log) {
		when(repository.findById(1L)).thenReturn(Optional.of(log));
	}

	@Test
	void shouldMarkSuccessWithShaWhenWriterSucceeds() {
		CommitLog log = pendingLog();
		found(log);
		when(writer.append(anyString(), anyString(), anyString(), any())).thenReturn("sha-1");

		service.process(1L);

		assertThat(log.getStatus()).isEqualTo(CommitStatus.SUCCESS);
		assertThat(log.getGithubCommitSha()).isEqualTo("sha-1");
		verify(repository).save(log);
	}

	@Test
	void shouldWriteLineWithGlobalSequenceAndCreationTimestamp() {
		found(pendingLog());
		when(writer.append(anyString(), anyString(), anyString(), any())).thenReturn("sha-1");

		service.process(1L);

		ArgumentCaptor<Instant> when = ArgumentCaptor.forClass(Instant.class);
		verify(writer).append(eq("logs/2026-10.md"), eq("Commit 7"), eq("Commit 7"), when.capture());
		assertThat(when.getValue()).isEqualTo(CREATED);
	}

	@Test
	void shouldNeverSendTypedTextToTheWriter() {
		found(pendingLog());
		when(writer.append(anyString(), anyString(), anyString(), any())).thenReturn("sha-1");

		service.process(1L);

		ArgumentCaptor<String> strings = ArgumentCaptor.forClass(String.class);
		verify(writer).append(strings.capture(), strings.capture(), strings.capture(), any());
		assertThat(strings.getAllValues()).noneMatch(value -> value.contains(TYPED_TEXT));
	}

	@Test
	void shouldReplyConfirmationOnSuccess() {
		found(pendingLog());
		when(writer.append(anyString(), anyString(), anyString(), any())).thenReturn("sha-1");

		service.process(1L);

		verify(telegramClient).sendMessage(100L, "Commit 7 registrado.");
	}

	@Test
	void shouldMarkFailedAndNotifyOnFirstFailure() {
		CommitLog log = pendingLog();
		found(log);
		doThrow(new GitHubTransientException("down")).when(writer).append(anyString(), anyString(), anyString(), any());

		service.process(1L);

		assertThat(log.getStatus()).isEqualTo(CommitStatus.FAILED);
		assertThat(log.getAttemptCount()).isEqualTo(1);
		verify(repository).save(log);
		verify(telegramClient).sendMessage(eq(100L), contains("Commit 7"));
	}

	@Test
	void shouldReplyFailureReasonWithoutSecretsOrTypedText() {
		found(pendingLog());
		doThrow(new GitHubPermanentException("status 401")).when(writer)
			.append(anyString(), anyString(), anyString(), any());

		service.process(1L);

		ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
		verify(telegramClient).sendMessage(eq(100L), text.capture());
		assertThat(text.getValue()).doesNotContain(TYPED_TEXT).doesNotContain("gh-token").doesNotContain("401");
	}

	@Test
	void shouldNotNotifyOnIntermediateFailure() {
		CommitLog log = logWithFailures(1);
		found(log);
		doThrow(new GitHubTransientException("down")).when(writer).append(anyString(), anyString(), anyString(), any());

		service.process(1L);

		assertThat(log.getAttemptCount()).isEqualTo(2);
		verify(telegramClient, never()).sendMessage(org.mockito.ArgumentMatchers.anyLong(), anyString());
	}

	@Test
	void shouldNotifyOnFinalFailure() {
		CommitLog log = logWithFailures(2);
		found(log);
		doThrow(new GitHubTransientException("down")).when(writer).append(anyString(), anyString(), anyString(), any());

		service.process(1L);

		assertThat(log.getAttemptCount()).isEqualTo(3);
		ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
		verify(telegramClient).sendMessage(eq(100L), text.capture());
		assertThat(text.getValue()).contains("definitivamente");
	}

	@Test
	void shouldReuseSequenceWhenReprocessingFailedRecord() {
		CommitLog log = logWithFailures(1);
		found(log);
		when(writer.append(anyString(), anyString(), anyString(), any())).thenReturn("sha-2");

		service.process(1L);

		verify(writer).append(eq("logs/2026-10.md"), eq("Commit 7"), eq("Commit 7"), any());
		assertThat(log.getStatus()).isEqualTo(CommitStatus.SUCCESS);
		assertThat(log.getSequenceNumber()).isEqualTo(7L);
	}

	@Test
	void shouldSkipRecordThatAlreadySucceeded() {
		CommitLog log = pendingLog();
		log.markSuccess("sha", CREATED);
		found(log);

		service.process(1L);

		verify(writer, never()).append(anyString(), anyString(), anyString(), any());
		verify(telegramClient, never()).sendMessage(org.mockito.ArgumentMatchers.anyLong(), anyString());
	}

	@Test
	void shouldIgnoreUnknownRecordWithWarnLog() {
		when(repository.findById(1L)).thenReturn(Optional.empty());

		service.process(1L);

		verify(writer, never()).append(anyString(), anyString(), anyString(), any());
		assertThat(appender.list).anyMatch(event -> event.getLevel() == Level.WARN);
	}

	@Test
	void shouldLogErrorWithExceptionOnUnexpectedFailure() {
		CommitLog log = pendingLog();
		found(log);
		IllegalStateException unexpected = new IllegalStateException("unexpected");
		doThrow(unexpected).when(writer).append(anyString(), anyString(), anyString(), any());

		service.process(1L);

		assertThat(log.getStatus()).isEqualTo(CommitStatus.FAILED);
		assertThat(appender.list).anyMatch(event -> event.getLevel() == Level.ERROR
				&& event.getThrowableProxy() != null
				&& event.getThrowableProxy().getMessage().equals("unexpected"));
	}

	@Test
	void shouldKeepRecordStateWhenTelegramNotificationFails() {
		CommitLog log = pendingLog();
		found(log);
		when(writer.append(anyString(), anyString(), anyString(), any())).thenReturn("sha-1");
		doThrow(new TelegramApiException("down")).when(telegramClient)
			.sendMessage(org.mockito.ArgumentMatchers.anyLong(), anyString());

		service.process(1L);

		assertThat(log.getStatus()).isEqualTo(CommitStatus.SUCCESS);
		assertThat(appender.list).anyMatch(event -> event.getLevel() == Level.WARN);
	}

	@Test
	void shouldNeverLogTypedText() {
		found(pendingLog());
		doThrow(new GitHubTransientException("down")).when(writer).append(anyString(), anyString(), anyString(), any());

		service.process(1L);

		assertThat(appender.list).noneMatch(event -> event.getFormattedMessage().contains(TYPED_TEXT));
	}

}
