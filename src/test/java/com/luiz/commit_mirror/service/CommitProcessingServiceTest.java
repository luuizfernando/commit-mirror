package com.luiz.commit_mirror.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.luiz.commit_mirror.TestProperties;
import com.luiz.commit_mirror.domain.CommitLog;
import com.luiz.commit_mirror.domain.CommitStatus;
import com.luiz.commit_mirror.integration.GitHubPermanentException;
import com.luiz.commit_mirror.integration.GitHubTransientException;
import com.luiz.commit_mirror.integration.TelegramApiException;
import com.luiz.commit_mirror.integration.TelegramClient;
import com.luiz.commit_mirror.repository.CommitLogRepository;
import com.luiz.commit_mirror.support.LogCapture;

import ch.qos.logback.classic.Level;

class CommitProcessingServiceTest {

	private static final long ID = 1L;

	private static final long CHAT_ID = 100L;

	private static final Instant CREATED = Instant.parse("2026-10-21T15:00:00Z");

	private static final Instant NOW = Instant.parse("2026-10-21T16:00:00Z");

	private static final String TYPED_TEXT = "private-typed-text";

	private final CommitLogRepository repository = mock(CommitLogRepository.class);

	private final GitHubLogWriter writer = mock(GitHubLogWriter.class);

	private final TelegramClient telegramClient = mock(TelegramClient.class);

	private final CommitProcessingService service = new CommitProcessingService(repository, writer,
			new CommitMessageFormatter(), new LogLineFormatter(), new LogFilePathResolver(TestProperties.defaults()),
			telegramClient, Clock.fixed(NOW, ZoneOffset.UTC), TestProperties.defaults());

	private final LogCapture logs = LogCapture.attach(CommitProcessingService.class);

	@AfterEach
	void detachLogs() {
		logs.detach();
	}

	private static CommitLog pendingLog() {
		return CommitLog.create(10L, CHAT_ID, TYPED_TEXT, LocalDate.of(2026, 10, 21), 7L, CREATED);
	}

	/** A FAILED record that already used {@code failures} attempts, as the recovery job would find it. */
	private static CommitLog failedLog(int failures) {
		CommitLog log = pendingLog();
		log.markFailed(CREATED);
		for (int i = 1; i < failures; i++) {
			log.reopen(CREATED);
			log.markFailed(CREATED);
		}
		return log;
	}

	private CommitLog stored(CommitLog log) {
		when(repository.findById(ID)).thenReturn(Optional.of(log));
		return log;
	}

	private void writerReturns(String sha) {
		when(writer.append(anyString(), anyString(), anyString(), any())).thenReturn(sha);
	}

	private void writerThrows(RuntimeException exception) {
		doThrow(exception).when(writer).append(anyString(), anyString(), anyString(), any());
	}

	private String sentText() {
		ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
		verify(telegramClient).sendMessage(eq(CHAT_ID), text.capture());
		return text.getValue();
	}

	// happy path

	@Test
	void shouldMarkRecordAsSuccessWhenWriterSucceeds() {
		CommitLog log = stored(pendingLog());
		writerReturns("sha-1");

		service.process(ID);

		assertThat(log.getStatus()).isEqualTo(CommitStatus.SUCCESS);
	}

	@Test
	void shouldStoreTheGitHubCommitShaWhenWriterSucceeds() {
		CommitLog log = stored(pendingLog());
		writerReturns("sha-1");

		service.process(ID);

		assertThat(log.getGithubCommitSha()).isEqualTo("sha-1");
	}

	@Test
	void shouldStampSuccessWithTheClockInstant() {
		CommitLog log = stored(pendingLog());
		writerReturns("sha-1");

		service.process(ID);

		assertThat(log.getUpdatedAt()).isEqualTo(NOW);
	}

	@Test
	void shouldPersistTheRecordWhenWriterSucceeds() {
		CommitLog log = stored(pendingLog());
		writerReturns("sha-1");

		service.process(ID);

		verify(repository).save(log);
	}

	@Test
	void shouldMarkSuccessWhenLineWasAlreadyInTheFile() {
		CommitLog log = stored(pendingLog());
		writerReturns(null);

		service.process(ID);

		assertThat(log.getStatus()).isEqualTo(CommitStatus.SUCCESS);
	}

	@Test
	void shouldWriteToTheMonthFileOfTheCommitDate() {
		stored(pendingLog());
		writerReturns("sha-1");

		service.process(ID);

		verify(writer).append(eq("logs/2026-10.md"), anyString(), anyString(), any());
	}

	@Test
	void shouldWriteLineWithTheGlobalSequence() {
		stored(pendingLog());
		writerReturns("sha-1");

		service.process(ID);

		verify(writer).append(anyString(), eq("Commit 7"), anyString(), any());
	}

	@Test
	void shouldWriteCommitMessageWithTheGlobalSequence() {
		stored(pendingLog());
		writerReturns("sha-1");

		service.process(ID);

		verify(writer).append(anyString(), anyString(), eq("Commit 7"), any());
	}

	@Test
	void shouldDateTheGitCommitWithTheCreationTimestampNotTheProcessingTime() {
		stored(pendingLog());
		writerReturns("sha-1");

		service.process(ID);

		verify(writer).append(anyString(), anyString(), anyString(), eq(CREATED));
	}

	@Test
	void shouldReplyConfirmationOnSuccess() {
		stored(pendingLog());
		writerReturns("sha-1");

		service.process(ID);

		verify(telegramClient).sendMessage(CHAT_ID, "Commit 7 registrado.");
	}

	// reprocessing

	@Test
	void shouldWriteTheSameSequenceWhenReprocessingFailedRecord() {
		stored(failedLog(1));
		writerReturns("sha-2");

		service.process(ID);

		verify(writer).append(anyString(), eq("Commit 7"), eq("Commit 7"), any());
	}

	@Test
	void shouldMarkReprocessedFailedRecordAsSuccess() {
		CommitLog log = stored(failedLog(1));
		writerReturns("sha-2");

		service.process(ID);

		assertThat(log.getStatus()).isEqualTo(CommitStatus.SUCCESS);
	}

	@Test
	void shouldNotWriteRecordThatAlreadySucceeded() {
		CommitLog log = pendingLog();
		log.markSuccess("sha", CREATED);
		stored(log);

		service.process(ID);

		verifyNoInteractions(writer);
	}

	@Test
	void shouldNotNotifyRecordThatAlreadySucceeded() {
		CommitLog log = pendingLog();
		log.markSuccess("sha", CREATED);
		stored(log);

		service.process(ID);

		verifyNoInteractions(telegramClient);
	}

	@Test
	void shouldNotWriteUnknownRecord() {
		when(repository.findById(ID)).thenReturn(Optional.empty());

		service.process(ID);

		verifyNoInteractions(writer);
	}

	@Test
	void shouldLogWarnForUnknownRecord() {
		when(repository.findById(ID)).thenReturn(Optional.empty());

		service.process(ID);

		assertThat(logs.hasEventAt(Level.WARN)).isTrue();
	}

	// GitHub failures

	@Test
	void shouldMarkRecordAsFailedWhenGitHubIsUnavailable() {
		CommitLog log = stored(pendingLog());
		writerThrows(new GitHubTransientException("down"));

		service.process(ID);

		assertThat(log.getStatus()).isEqualTo(CommitStatus.FAILED);
	}

	@Test
	void shouldCountTheFirstFailedAttempt() {
		CommitLog log = stored(pendingLog());
		writerThrows(new GitHubTransientException("down"));

		service.process(ID);

		assertThat(log.getAttemptCount()).isEqualTo(1);
	}

	@Test
	void shouldPersistTheRecordWhenGitHubFails() {
		CommitLog log = stored(pendingLog());
		writerThrows(new GitHubTransientException("down"));

		service.process(ID);

		verify(repository).save(log);
	}

	@Test
	void shouldTellUserItWillRetryOnFirstFailure() {
		stored(pendingLog());
		writerThrows(new GitHubTransientException("down"));

		service.process(ID);

		assertThat(sentText()).contains("Commit 7").contains("tentar novamente");
	}

	@Test
	void shouldReplyThatGitHubIsUnavailableOnTransientFailure() {
		stored(pendingLog());
		writerThrows(new GitHubTransientException("down"));

		service.process(ID);

		assertThat(sentText()).contains("GitHub indisponível");
	}

	@Test
	void shouldAskToCheckTheTokenOnPermanentFailure() {
		stored(pendingLog());
		writerThrows(new GitHubPermanentException("status 401"));

		service.process(ID);

		assertThat(sentText()).contains("verifique o token");
	}

	@Test
	void shouldReplyFailureReasonWithoutSecretsStatusOrTypedText() {
		stored(pendingLog());
		writerThrows(new GitHubPermanentException("status 401"));

		service.process(ID);

		assertThat(sentText()).doesNotContain(TYPED_TEXT).doesNotContain("gh-token").doesNotContain("401");
	}

	@Test
	void shouldCountTheAttemptOnIntermediateFailure() {
		CommitLog log = stored(failedLog(1));
		writerThrows(new GitHubTransientException("down"));

		service.process(ID);

		assertThat(log.getAttemptCount()).isEqualTo(2);
	}

	@Test
	void shouldNotNotifyOnIntermediateFailure() {
		stored(failedLog(1));
		writerThrows(new GitHubTransientException("down"));

		service.process(ID);

		verify(telegramClient, never()).sendMessage(anyLong(), anyString());
	}

	@Test
	void shouldCountTheAttemptOnFinalFailure() {
		CommitLog log = stored(failedLog(2));
		writerThrows(new GitHubTransientException("down"));

		service.process(ID);

		assertThat(log.getAttemptCount()).isEqualTo(TestProperties.recovery().maxAttempts());
	}

	@Test
	void shouldTellUserItGaveUpOnFinalFailure() {
		stored(failedLog(2));
		writerThrows(new GitHubTransientException("down"));

		service.process(ID);

		assertThat(sentText()).contains("definitivamente");
	}

	// unexpected failures

	@Test
	void shouldMarkRecordAsFailedOnUnexpectedFailure() {
		CommitLog log = stored(pendingLog());
		writerThrows(new IllegalStateException("unexpected"));

		service.process(ID);

		assertThat(log.getStatus()).isEqualTo(CommitStatus.FAILED);
	}

	@Test
	void shouldLogErrorWithTheExceptionOnUnexpectedFailure() {
		stored(pendingLog());
		writerThrows(new IllegalStateException("unexpected"));

		service.process(ID);

		assertThat(logs.hasErrorWithThrowableMessage("unexpected")).isTrue();
	}

	@Test
	void shouldReplyGenericReasonOnUnexpectedFailure() {
		stored(pendingLog());
		writerThrows(new IllegalStateException("unexpected"));

		service.process(ID);

		assertThat(sentText()).contains("erro inesperado");
	}

	// Telegram failures

	@Test
	void shouldKeepSuccessWhenTelegramNotificationFails() {
		CommitLog log = stored(pendingLog());
		writerReturns("sha-1");
		doThrow(new TelegramApiException("down")).when(telegramClient).sendMessage(anyLong(), anyString());

		service.process(ID);

		assertThat(log.getStatus()).isEqualTo(CommitStatus.SUCCESS);
	}

	@Test
	void shouldLogWarnWhenTelegramNotificationFails() {
		stored(pendingLog());
		writerReturns("sha-1");
		doThrow(new TelegramApiException("down")).when(telegramClient).sendMessage(anyLong(), anyString());

		service.process(ID);

		assertThat(logs.hasEventAt(Level.WARN)).isTrue();
	}

	// privacy

	@Test
	void shouldNeverSendTypedTextToTheWriter() {
		stored(pendingLog());
		writerReturns("sha-1");

		service.process(ID);

		ArgumentCaptor<String> strings = ArgumentCaptor.forClass(String.class);
		verify(writer).append(strings.capture(), strings.capture(), strings.capture(), any());
		assertThat(strings.getAllValues()).noneMatch(value -> value.contains(TYPED_TEXT));
	}

	@Test
	void shouldNeverLogTypedTextOnSuccess() {
		stored(pendingLog());
		writerReturns("sha-1");

		service.process(ID);

		assertThat(logs.anyEventContains(TYPED_TEXT)).isFalse();
	}

	@Test
	void shouldNeverLogTypedTextOnFailure() {
		stored(pendingLog());
		writerThrows(new GitHubTransientException("down"));

		service.process(ID);

		assertThat(logs.anyEventContains(TYPED_TEXT)).isFalse();
	}

}
