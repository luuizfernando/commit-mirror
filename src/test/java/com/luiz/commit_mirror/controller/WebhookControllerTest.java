package com.luiz.commit_mirror.controller;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.luiz.commit_mirror.dto.TelegramChat;
import com.luiz.commit_mirror.dto.TelegramMessage;
import com.luiz.commit_mirror.dto.TelegramUpdate;
import com.luiz.commit_mirror.dto.TelegramUser;
import com.luiz.commit_mirror.exception.InvalidWebhookSecretException;
import com.luiz.commit_mirror.service.UpdateDispatcher;
import com.luiz.commit_mirror.service.WebhookSecretValidator;

@ActiveProfiles("test")
@WebMvcTest(WebhookController.class)
class WebhookControllerTest {

	private static final String SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token";

	private static final String VALID_SECRET = "ok";

	private static final String WRONG_SECRET = "wrong";

	private static final String COMMIT_UPDATE_WITH_UNMAPPED_FIELDS = """
			{"update_id": 10, "message": {"message_id": 5, "from": {"id": 42, "is_bot": false},
			 "chat": {"id": 100, "type": "private"}, "text": "/commit hello", "date": 1700000000}}""";

	private static final String UPDATE_WITHOUT_TEXT = """
			{"update_id": 11, "message": {"message_id": 6, "chat": {"id": 100}}}""";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private WebhookSecretValidator secretValidator;

	@MockitoBean
	private UpdateDispatcher dispatcher;

	private static MockHttpServletRequestBuilder webhook(String body) {
		return post("/telegram/webhook").contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private ResultActions postWithSecret(String secret, String body) throws Exception {
		return mockMvc.perform(webhook(body).header(SECRET_HEADER, secret));
	}

	private void secretIsRejected(String secret) {
		doThrow(new InvalidWebhookSecretException()).when(secretValidator).validate(secret);
	}

	@Test
	void shouldReturnEmpty200ForValidUpdate() throws Exception {
		postWithSecret(VALID_SECRET, COMMIT_UPDATE_WITH_UNMAPPED_FIELDS).andExpect(status().isOk()).andExpect(content().string(""));
	}

	@Test
	void shouldValidateTheSecretFromTheHeader() throws Exception {
		postWithSecret(VALID_SECRET, COMMIT_UPDATE_WITH_UNMAPPED_FIELDS);

		verify(secretValidator).validate(VALID_SECRET);
	}

	@Test
	void shouldDispatchTheDeserializedUpdate() throws Exception {
		postWithSecret(VALID_SECRET, COMMIT_UPDATE_WITH_UNMAPPED_FIELDS);

		verify(dispatcher).dispatch(new TelegramUpdate(10L,
				new TelegramMessage(5L, new TelegramUser(42L), new TelegramChat(100L), "/commit hello")));
	}

	@Test
	void shouldReturnEmpty200WhenMessageHasNoText() throws Exception {
		postWithSecret(VALID_SECRET, UPDATE_WITHOUT_TEXT).andExpect(status().isOk()).andExpect(content().string(""));
	}

	@Test
	void shouldDispatchUpdateWithoutTextLeavingMissingFieldsNull() throws Exception {
		postWithSecret(VALID_SECRET, UPDATE_WITHOUT_TEXT);

		verify(dispatcher).dispatch(new TelegramUpdate(11L, new TelegramMessage(6L, null, new TelegramChat(100L), null)));
	}

	@Test
	void shouldReturnEmpty403WhenSecretIsInvalid() throws Exception {
		secretIsRejected(WRONG_SECRET);

		postWithSecret(WRONG_SECRET, COMMIT_UPDATE_WITH_UNMAPPED_FIELDS).andExpect(status().isForbidden()).andExpect(content().string(""));
	}

	@Test
	void shouldReturn403WhenSecretHeaderIsMissing() throws Exception {
		secretIsRejected(null);

		mockMvc.perform(webhook(COMMIT_UPDATE_WITH_UNMAPPED_FIELDS)).andExpect(status().isForbidden());
	}

	@Test
	void shouldReturn403WhenSecretIsInvalidEvenIfBodyIsMalformed() throws Exception {
		secretIsRejected(WRONG_SECRET);

		postWithSecret(WRONG_SECRET, "{not json").andExpect(status().isForbidden());
	}

	@Test
	void shouldReturn403WhenSecretHeaderIsMissingEvenIfBodyIsMalformed() throws Exception {
		secretIsRejected(null);

		mockMvc.perform(webhook("{not json")).andExpect(status().isForbidden());
	}

	@Test
	void shouldNotDispatchWhenSecretIsInvalid() throws Exception {
		secretIsRejected(WRONG_SECRET);

		postWithSecret(WRONG_SECRET, COMMIT_UPDATE_WITH_UNMAPPED_FIELDS);

		verifyNoInteractions(dispatcher);
	}

	@Test
	void shouldReturnEmpty400WhenBodyIsMalformed() throws Exception {
		postWithSecret(VALID_SECRET, "{not json").andExpect(status().isBadRequest()).andExpect(content().string(""));
	}

	@Test
	void shouldNotDispatchWhenBodyIsMalformed() throws Exception {
		postWithSecret(VALID_SECRET, "{not json");

		verifyNoInteractions(dispatcher);
	}

	@Test
	void shouldReturn400WhenBodyIsEmpty() throws Exception {
		postWithSecret(VALID_SECRET, "").andExpect(status().isBadRequest());
	}

}
