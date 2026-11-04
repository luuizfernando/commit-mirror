package com.luiz.commit_mirror.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.luiz.commit_mirror.domain.MostActiveDays;
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

	private void mostActiveDaysAre(long commits, LocalDate... dates) {
		when(commitLogService.findMostActiveDays())
			.thenReturn(Optional.of(new MostActiveDays(List.of(dates), commits)));
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

	@Test
	void shouldAppendTheMostActiveDayWhenOneDayHasTheMostCommits() {
		mirroredCommitsAre(42L);
		mostActiveDaysAre(7L, LocalDate.of(2026, 10, 15));

		command.execute(context(""));

		verify(telegramClient).sendMessage(CHAT_ID,
				"Você já salvou 42 commits com o Commit Mirror.\nSeu dia mais ativo foi 15/10/2026, com 7 commits.");
	}

	@Test
	void shouldUseSingularCommitWhenTheMostActiveDayHasOneCommit() {
		mirroredCommitsAre(1L);
		mostActiveDaysAre(1L, LocalDate.of(2026, 10, 15));

		command.execute(context(""));

		verify(telegramClient).sendMessage(CHAT_ID,
				"Você já salvou 1 commit com o Commit Mirror.\nSeu dia mais ativo foi 15/10/2026, com 1 commit.");
	}

	@Test
	void shouldListBothDaysJoinedByEWhenTwoDaysTie() {
		mirroredCommitsAre(14L);
		mostActiveDaysAre(7L, LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 16));

		command.execute(context(""));

		verify(telegramClient).sendMessage(CHAT_ID,
				"Você já salvou 14 commits com o Commit Mirror.\nSeus dias mais ativos foram 15/10/2026 e 16/10/2026, com 7 commits cada.");
	}

	@Test
	void shouldSeparateDaysWithCommasAndTheLastWithEWhenThreeDaysTie() {
		mirroredCommitsAre(6L);
		mostActiveDaysAre(2L, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 3));

		command.execute(context(""));

		verify(telegramClient).sendMessage(CHAT_ID,
				"Você já salvou 6 commits com o Commit Mirror.\nSeus dias mais ativos foram 01/10/2026, 02/10/2026 e 03/10/2026, com 2 commits cada.");
	}

	@Test
	void shouldUseSingularCommitEachWhenTiedDaysHaveOneCommit() {
		mirroredCommitsAre(2L);
		mostActiveDaysAre(1L, LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 16));

		command.execute(context(""));

		verify(telegramClient).sendMessage(CHAT_ID,
				"Você já salvou 2 commits com o Commit Mirror.\nSeus dias mais ativos foram 15/10/2026 e 16/10/2026, com 1 commit cada.");
	}

	@Test
	void shouldNotLookUpMostActiveDaysWhenNothingWasMirrored() {
		mirroredCommitsAre(0L);

		command.execute(context(""));

		verify(commitLogService, never()).findMostActiveDays();
	}

}
