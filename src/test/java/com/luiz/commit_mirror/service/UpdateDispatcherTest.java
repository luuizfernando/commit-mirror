package com.luiz.commit_mirror.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.luiz.commit_mirror.TestProperties;
import com.luiz.commit_mirror.config.AppProperties;
import com.luiz.commit_mirror.dto.TelegramChat;
import com.luiz.commit_mirror.dto.TelegramMessage;
import com.luiz.commit_mirror.dto.TelegramUpdate;
import com.luiz.commit_mirror.dto.TelegramUser;
import com.luiz.commit_mirror.integration.TelegramClient;
import com.luiz.commit_mirror.service.command.BotCommand;
import com.luiz.commit_mirror.service.command.CommandContext;
import com.luiz.commit_mirror.service.command.CommandRegistry;

import static org.assertj.core.api.Assertions.assertThat;

class UpdateDispatcherTest {

	private static final long ALLOWED_USER = 42L;

	private final TelegramClient telegramClient = mock(TelegramClient.class);

	private final BotCommand commitCommand = mock(BotCommand.class);

	private final UpdateDispatcher dispatcher;

	UpdateDispatcherTest() {
		when(commitCommand.name()).thenReturn("commit");
		AppProperties properties = TestProperties.withTelegram(
				new AppProperties.Telegram("token", "secret", ALLOWED_USER, "http://telegram"));
		this.dispatcher = new UpdateDispatcher(new UserAllowlist(properties), new CommandParser(),
				new CommandRegistry(List.of(commitCommand)), telegramClient);
	}

	@Test
	void shouldIgnoreUpdateWithoutMessage() {
		dispatcher.dispatch(new TelegramUpdate(1L, null));

		verify(commitCommand, never()).execute(any());
		verifyNoInteractions(telegramClient);
	}

	@Test
	void shouldIgnoreMessageWithoutText() {
		dispatcher.dispatch(update(ALLOWED_USER, null));

		verify(commitCommand, never()).execute(any());
		verifyNoInteractions(telegramClient);
	}

	@Test
	void shouldIgnoreUserOutsideAllowlistSilently() {
		dispatcher.dispatch(update(7L, "/commit hello"));

		verify(commitCommand, never()).execute(any());
		verifyNoInteractions(telegramClient);
	}

	@Test
	void shouldReplyWhenCommandIsUnknown() {
		dispatcher.dispatch(update(ALLOWED_USER, "/unknown"));

		verify(telegramClient).sendMessage(100L, "Comando desconhecido: /unknown");
		verify(commitCommand, never()).execute(any());
	}

	@Test
	void shouldExecuteResolvedCommandWithContext() {
		dispatcher.dispatch(update(ALLOWED_USER, "/commit hello world"));

		ArgumentCaptor<CommandContext> captor = ArgumentCaptor.forClass(CommandContext.class);
		verify(commitCommand).execute(captor.capture());
		assertThat(captor.getValue()).isEqualTo(new CommandContext(1L, 100L, ALLOWED_USER, "hello world"));
	}

	private static TelegramUpdate update(long userId, String text) {
		return new TelegramUpdate(1L, new TelegramMessage(5L, new TelegramUser(userId), new TelegramChat(100L), text));
	}

}
