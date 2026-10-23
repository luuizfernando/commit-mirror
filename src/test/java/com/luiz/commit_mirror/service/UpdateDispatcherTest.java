package com.luiz.commit_mirror.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.luiz.commit_mirror.TestProperties;
import com.luiz.commit_mirror.dto.TelegramChat;
import com.luiz.commit_mirror.dto.TelegramMessage;
import com.luiz.commit_mirror.dto.TelegramUpdate;
import com.luiz.commit_mirror.dto.TelegramUser;
import com.luiz.commit_mirror.integration.TelegramClient;
import com.luiz.commit_mirror.service.command.BotCommand;
import com.luiz.commit_mirror.service.command.CommandContext;
import com.luiz.commit_mirror.service.command.CommandRegistry;

class UpdateDispatcherTest {

	private static final long UPDATE_ID = 1L;

	private static final long CHAT_ID = 100L;

	private static final long ALLOWED_USER = TestProperties.telegram().allowedUserId();

	private static final long OTHER_USER = 7L;

	private final TelegramClient telegramClient = mock(TelegramClient.class);

	private final BotCommand commitCommand = mock(BotCommand.class);

	private final UpdateDispatcher dispatcher;

	UpdateDispatcherTest() {
		when(commitCommand.name()).thenReturn("commit");
		this.dispatcher = new UpdateDispatcher(new UserAllowlist(TestProperties.defaults()), new CommandParser(),
				new CommandRegistry(List.of(commitCommand)), telegramClient);
	}

	private static TelegramUpdate update(TelegramUser from, TelegramChat chat, String text) {
		return new TelegramUpdate(UPDATE_ID, new TelegramMessage(5L, from, chat, text));
	}

	private static TelegramUpdate update(long userId, String text) {
		return update(new TelegramUser(userId), new TelegramChat(CHAT_ID), text);
	}

	/** An ignored update runs no command and sends no reply. */
	private void verifyIgnoredSilently() {
		verify(commitCommand, never()).execute(any());
		verifyNoInteractions(telegramClient);
	}

	// happy path

	@Test
	void shouldExecuteResolvedCommandWithContext() {
		dispatcher.dispatch(update(ALLOWED_USER, "/commit hello world"));

		verify(commitCommand).execute(new CommandContext(UPDATE_ID, CHAT_ID, ALLOWED_USER, "hello world"));
	}

	// ignored updates

	@Test
	void shouldIgnoreUpdateWithoutMessage() {
		dispatcher.dispatch(new TelegramUpdate(UPDATE_ID, null));

		verifyIgnoredSilently();
	}

	@Test
	void shouldIgnoreMessageWithoutText() {
		dispatcher.dispatch(update(ALLOWED_USER, null));

		verifyIgnoredSilently();
	}

	@Test
	void shouldIgnoreMessageWithoutSender() {
		dispatcher.dispatch(update(null, new TelegramChat(CHAT_ID), "/commit hello"));

		verifyIgnoredSilently();
	}

	@Test
	void shouldIgnoreMessageWithoutChat() {
		dispatcher.dispatch(update(new TelegramUser(ALLOWED_USER), null, "/commit hello"));

		verifyIgnoredSilently();
	}

	@Test
	void shouldIgnoreUserOutsideAllowlistSilently() {
		dispatcher.dispatch(update(OTHER_USER, "/commit hello"));

		verifyIgnoredSilently();
	}

	@Test
	void shouldIgnoreTextThatIsNotACommand() {
		dispatcher.dispatch(update(ALLOWED_USER, "just chatting"));

		verifyIgnoredSilently();
	}

	@Test
	void shouldNotExecuteCommitWhenTextIsOnlyASlash() {
		dispatcher.dispatch(update(ALLOWED_USER, "/"));

		verify(commitCommand, never()).execute(any());
	}

	@Test
	void shouldNotExecuteCommitWhenCommandIsCapitalized() {
		dispatcher.dispatch(update(ALLOWED_USER, "/Commit hello"));

		verify(commitCommand, never()).execute(any());
	}

	// unknown command

	@Test
	void shouldReplyWhenCommandIsUnknown() {
		dispatcher.dispatch(update(ALLOWED_USER, "/unknown"));

		verify(telegramClient).sendMessage(CHAT_ID, "Comando desconhecido: /unknown");
	}

	@Test
	void shouldNotExecuteAnyCommandWhenCommandIsUnknown() {
		dispatcher.dispatch(update(ALLOWED_USER, "/unknown"));

		verify(commitCommand, never()).execute(any());
	}

}
