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
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import com.luiz.commit_mirror.domain.CommitLog;
import com.luiz.commit_mirror.integration.TelegramClient;
import com.luiz.commit_mirror.service.CommitLogService;
import com.luiz.commit_mirror.service.CommitProcessingService;
import com.luiz.commit_mirror.service.LogWriteSerializer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class CommitCommandTest {

	private static final long CHAT_ID = 100L;

	private static final String USAGE = "Uso: /commit <mensagem>";

	private final TelegramClient telegramClient = mock(TelegramClient.class);

	private final CommitLogService commitLogService = mock(CommitLogService.class);

	private final LogWriteSerializer serializer = mock(LogWriteSerializer.class);

	private final CommitProcessingService processing = mock(CommitProcessingService.class);

	private final CommitCommand command = new CommitCommand(telegramClient, commitLogService, serializer,
			processing);

	private final Logger logger = (Logger) LoggerFactory.getLogger(CommitCommand.class);

	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

	@BeforeEach
	void attachAppender() {
		logger.setLevel(Level.DEBUG);
		appender.start();
		logger.addAppender(appender);
		when(serializer.submit(any(Runnable.class))).thenReturn(CompletableFuture.completedFuture(null));
	}

	@AfterEach
	void detachAppender() {
		logger.detachAppender(appender);
	}

	private static CommitLog registered(long id) {
		CommitLog log = CommitLog.create(1L, CHAT_ID, "typed", LocalDate.of(2026, 10, 21), 7L,
				Instant.parse("2026-10-21T15:00:00Z"));
		ReflectionTestUtils.setField(log, "id", id);
		return log;
	}

	private static CommandContext context(String arguments) {
		return new CommandContext(1L, CHAT_ID, 42L, arguments);
	}

	@Test
	void shouldReplyUsageWhenMessageIsEmpty() {
		command.execute(context(""));

		verify(telegramClient).sendMessage(CHAT_ID, USAGE);
		verifyNoInteractions(commitLogService, serializer);
	}

	@Test
	void shouldReplyUsageWhenMessageIsBlank() {
		command.execute(context("   "));

		verify(telegramClient).sendMessage(CHAT_ID, USAGE);
		verifyNoInteractions(commitLogService, serializer);
	}

	@Test
	void shouldLogWarnWhenMessageIsEmpty() {
		command.execute(context(""));

		assertThat(appender.list).anyMatch(event -> event.getLevel() == Level.WARN);
	}

	@Test
	void shouldSaveRecordAsPendingBeforeSubmittingProcessing() {
		when(commitLogService.register(1L, CHAT_ID, "hello")).thenReturn(Optional.of(registered(5L)));

		command.execute(context("hello"));

		InOrder order = inOrder(commitLogService, serializer);
		order.verify(commitLogService).register(1L, CHAT_ID, "hello");
		order.verify(serializer).submit(any(Runnable.class));
	}

	@Test
	void shouldProcessTheRegisteredRecordThroughTheSerializer() {
		when(commitLogService.register(1L, CHAT_ID, "hello")).thenReturn(Optional.of(registered(5L)));

		command.execute(context("hello"));

		ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
		verify(serializer).submit(task.capture());
		verify(processing, never()).process(5L);
		task.getValue().run();
		verify(processing).process(5L);
	}

	@Test
	void shouldIgnoreDuplicateUpdateWithWarnLog() {
		when(commitLogService.register(1L, CHAT_ID, "hello")).thenReturn(Optional.empty());

		command.execute(context("hello"));

		verifyNoInteractions(serializer, telegramClient);
		assertThat(appender.list).anyMatch(event -> event.getLevel() == Level.WARN);
	}

	@Test
	void shouldLogInfoOnExecution() {
		when(commitLogService.register(1L, CHAT_ID, "hello")).thenReturn(Optional.of(registered(5L)));

		command.execute(context("hello"));

		assertThat(appender.list).anyMatch(event -> event.getLevel() == Level.INFO);
	}

	@Test
	void shouldNeverLogTypedText() {
		when(commitLogService.register(1L, CHAT_ID, "super secret typed text")).thenReturn(Optional.of(registered(5L)));
		when(commitLogService.register(1L, CHAT_ID, "dup secret text")).thenReturn(Optional.empty());

		command.execute(context("super secret typed text"));
		command.execute(context("dup secret text"));
		command.execute(context(""));

		assertThat(appender.list).noneMatch(event -> event.getFormattedMessage().contains("secret"));
		assertThat(appender.list).noneMatch(event -> event.getArgumentArray() != null
				&& Arrays.stream(event.getArgumentArray()).anyMatch(arg -> String.valueOf(arg).contains("secret")));
	}

}
