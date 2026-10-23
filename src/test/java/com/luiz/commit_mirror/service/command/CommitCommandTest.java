package com.luiz.commit_mirror.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import com.luiz.commit_mirror.domain.CommitLog;
import com.luiz.commit_mirror.integration.TelegramClient;
import com.luiz.commit_mirror.service.CommitLogService;
import com.luiz.commit_mirror.service.CommitProcessingService;
import com.luiz.commit_mirror.service.LogWriteSerializer;
import com.luiz.commit_mirror.support.LogCapture;

import ch.qos.logback.classic.Level;

class CommitCommandTest {

	private static final long UPDATE_ID = 1L;

	private static final long CHAT_ID = 100L;

	private static final long RECORD_ID = 5L;

	private static final String USAGE = "Uso: /commit <mensagem>";

	private static final String TYPED_TEXT = "super secret typed text";

	private final TelegramClient telegramClient = mock(TelegramClient.class);

	private final CommitLogService commitLogService = mock(CommitLogService.class);

	private final LogWriteSerializer serializer = mock(LogWriteSerializer.class);

	private final CommitProcessingService processing = mock(CommitProcessingService.class);

	private final CommitCommand command = new CommitCommand(telegramClient, commitLogService, serializer,
			processing);

	private final LogCapture logs = LogCapture.attach(CommitCommand.class);

	@BeforeEach
	void serializerAcceptsTasks() {
		when(serializer.submit(any(Runnable.class))).thenReturn(CompletableFuture.completedFuture(null));
	}

	@AfterEach
	void detachLogs() {
		logs.detach();
	}

	private static CommitLog registered() {
		CommitLog log = CommitLog.create(UPDATE_ID, CHAT_ID, TYPED_TEXT, LocalDate.of(2026, 10, 21), 7L,
				Instant.parse("2026-10-21T15:00:00Z"));
		ReflectionTestUtils.setField(log, "id", RECORD_ID);
		return log;
	}

	private static CommandContext context(String arguments) {
		return new CommandContext(UPDATE_ID, CHAT_ID, 42L, arguments);
	}

	private void registrationSucceeds() {
		when(commitLogService.register(UPDATE_ID, CHAT_ID, TYPED_TEXT)).thenReturn(Optional.of(registered()));
	}

	private void updateIsDuplicate() {
		when(commitLogService.register(UPDATE_ID, CHAT_ID, TYPED_TEXT)).thenReturn(Optional.empty());
	}

	private Runnable submittedTask() {
		ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
		verify(serializer).submit(task.capture());
		return task.getValue();
	}

	@Test
	void shouldBeRegisteredUnderTheCommitName() {
		assertThat(command.name()).isEqualTo("commit");
	}

	// input validation

	@ParameterizedTest
	@ValueSource(strings = { "", "   " })
	void shouldReplyUsageWhenMessageIsEmptyOrBlank(String arguments) {
		command.execute(context(arguments));

		verify(telegramClient).sendMessage(CHAT_ID, USAGE);
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "   " })
	void shouldNotRegisterWhenMessageIsEmptyOrBlank(String arguments) {
		command.execute(context(arguments));

		verifyNoInteractions(commitLogService, serializer);
	}

	@Test
	void shouldLogWarnWhenMessageIsEmpty() {
		command.execute(context(""));

		assertThat(logs.hasEventAt(Level.WARN)).isTrue();
	}

	// happy path

	@Test
	void shouldRegisterTheRecordBeforeSubmittingProcessing() {
		registrationSucceeds();

		command.execute(context(TYPED_TEXT));

		InOrder order = inOrder(commitLogService, serializer);
		order.verify(commitLogService).register(UPDATE_ID, CHAT_ID, TYPED_TEXT);
		order.verify(serializer).submit(any(Runnable.class));
	}

	@Test
	void shouldNotProcessOutsideTheSerializer() {
		registrationSucceeds();

		command.execute(context(TYPED_TEXT));

		verify(processing, never()).process(RECORD_ID);
	}

	@Test
	void shouldProcessTheRegisteredRecordWhenTheSerializerRunsTheTask() {
		registrationSucceeds();
		command.execute(context(TYPED_TEXT));

		submittedTask().run();

		verify(processing).process(RECORD_ID);
	}

	@Test
	void shouldNotReplyDirectlyOnSuccess() {
		registrationSucceeds();

		command.execute(context(TYPED_TEXT));

		verifyNoInteractions(telegramClient);
	}

	@Test
	void shouldLogInfoWhenRecordIsRegistered() {
		registrationSucceeds();

		command.execute(context(TYPED_TEXT));

		assertThat(logs.hasEventAt(Level.INFO)).isTrue();
	}

	// duplicates

	@Test
	void shouldNotSubmitProcessingForDuplicateUpdate() {
		updateIsDuplicate();

		command.execute(context(TYPED_TEXT));

		verifyNoInteractions(serializer);
	}

	@Test
	void shouldNotReplyToDuplicateUpdate() {
		updateIsDuplicate();

		command.execute(context(TYPED_TEXT));

		verifyNoInteractions(telegramClient);
	}

	@Test
	void shouldLogWarnForDuplicateUpdate() {
		updateIsDuplicate();

		command.execute(context(TYPED_TEXT));

		assertThat(logs.hasEventAt(Level.WARN)).isTrue();
	}

	// privacy

	@Test
	void shouldNeverLogTypedTextWhenRecordIsRegistered() {
		registrationSucceeds();

		command.execute(context(TYPED_TEXT));

		assertThat(logs.anyEventContains("secret")).isFalse();
	}

	@Test
	void shouldNeverLogTypedTextForDuplicateUpdate() {
		updateIsDuplicate();

		command.execute(context(TYPED_TEXT));

		assertThat(logs.anyEventContains("secret")).isFalse();
	}

}
