package com.luiz.commit_mirror.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.luiz.commit_mirror.integration.TelegramClient;
import com.luiz.commit_mirror.service.CommitLogService;
import com.luiz.commit_mirror.support.LogCapture;

import ch.qos.logback.classic.Level;

class StatusCommandTest {

	private static final long UPDATE_ID = 1L;

	private static final long CHAT_ID = 100L;

	private final TelegramClient telegramClient = mock(TelegramClient.class);

	private final CommitLogService commitLogService = mock(CommitLogService.class);

	private final StatusCommand command = new StatusCommand(telegramClient, commitLogService);

	private final LogCapture logs = LogCapture.attach(StatusCommand.class);

	@AfterEach
	void detachLogs() {
		logs.detach();
	}

	private static CommandContext context(String arguments) {
		return new CommandContext(UPDATE_ID, CHAT_ID, 42L, arguments);
	}

	private void mirroredCommitsAre(long count) {
		when(commitLogService.countMirroredCommits()).thenReturn(count);
	}

	@Test
	void shouldBeRegisteredUnderTheStatusName() {
		assertThat(command.name()).isEqualTo("status");
	}

	@Test
	void shouldReplyThePluralMessageWhenManyCommitsWereMirrored() {
		mirroredCommitsAre(42L);

		command.execute(context(""));

		verify(telegramClient).sendMessage(CHAT_ID, "Você já salvou 42 commits com o Commit Mirror.");
	}

	@Test
	void shouldReplySingularMessageWhenOneCommitWasMirrored() {
		mirroredCommitsAre(1L);

		command.execute(context(""));

		verify(telegramClient).sendMessage(CHAT_ID, "Você já salvou 1 commit com o Commit Mirror.");
	}

	@Test
	void shouldReplyNoCommitsMessageWhenNothingWasMirrored() {
		mirroredCommitsAre(0L);

		command.execute(context(""));

		verify(telegramClient).sendMessage(CHAT_ID, "Nenhum commit salvo ainda.");
	}

	@Test
	void shouldIgnoreArgumentsWhenReplying() {
		mirroredCommitsAre(2L);

		command.execute(context("anything typed after the command"));

		verify(telegramClient).sendMessage(CHAT_ID, "Você já salvou 2 commits com o Commit Mirror.");
	}

	@Test
	void shouldNotRegisterAnyCommit() {
		mirroredCommitsAre(2L);

		command.execute(context("anything"));

		verify(commitLogService, never()).register(anyLong(), anyLong(), anyString());
	}

	@Test
	void shouldLogInfoWhenStatusIsReplied() {
		mirroredCommitsAre(2L);

		command.execute(context(""));

		assertThat(logs.hasEventAt(Level.INFO)).isTrue();
	}

}
