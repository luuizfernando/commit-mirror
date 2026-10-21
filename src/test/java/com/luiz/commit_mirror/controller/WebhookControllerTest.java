package com.luiz.commit_mirror.controller;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.luiz.commit_mirror.dto.TelegramUpdate;
import com.luiz.commit_mirror.exception.InvalidWebhookSecretException;
import com.luiz.commit_mirror.service.UpdateDispatcher;
import com.luiz.commit_mirror.service.WebhookSecretValidator;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@WebMvcTest(WebhookController.class)
class WebhookControllerTest {

	private static final String SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token";

	private static final String COMMIT_UPDATE = """
			{"update_id": 10, "message": {"message_id": 5, "from": {"id": 42, "is_bot": false},
			 "chat": {"id": 100, "type": "private"}, "text": "/commit hello", "date": 1700000000}}""";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private WebhookSecretValidator secretValidator;

	@MockitoBean
	private UpdateDispatcher dispatcher;

	@Test
	void shouldReturn403WhenSecretIsInvalid() throws Exception {
		doThrow(new InvalidWebhookSecretException()).when(secretValidator).validate("wrong");

		mockMvc.perform(post("/telegram/webhook").header(SECRET_HEADER, "wrong")
			.contentType(MediaType.APPLICATION_JSON)
			.content(COMMIT_UPDATE)).andExpect(status().isForbidden()).andExpect(content().string(""));
	}

	@Test
	void shouldReturn403WhenSecretHeaderIsMissing() throws Exception {
		doThrow(new InvalidWebhookSecretException()).when(secretValidator).validate(null);

		mockMvc.perform(post("/telegram/webhook").contentType(MediaType.APPLICATION_JSON).content(COMMIT_UPDATE))
			.andExpect(status().isForbidden());
	}

	@Test
	void shouldNotCallServiceWhenSecretIsInvalid() throws Exception {
		doThrow(new InvalidWebhookSecretException()).when(secretValidator).validate("wrong");

		mockMvc.perform(post("/telegram/webhook").header(SECRET_HEADER, "wrong")
			.contentType(MediaType.APPLICATION_JSON)
			.content(COMMIT_UPDATE));

		verifyNoInteractions(dispatcher);
	}

	@Test
	void shouldReturnEmpty200WhenUserIsNotAllowed() throws Exception {
		mockMvc.perform(post("/telegram/webhook").header(SECRET_HEADER, "ok")
			.contentType(MediaType.APPLICATION_JSON)
			.content(COMMIT_UPDATE.replace("\"id\": 42", "\"id\": 7")))
			.andExpect(status().isOk())
			.andExpect(content().string(""));
	}

	@Test
	void shouldReturnEmpty200WhenMessageHasNoText() throws Exception {
		mockMvc.perform(post("/telegram/webhook").header(SECRET_HEADER, "ok")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"update_id\": 11, \"message\": {\"message_id\": 6, \"chat\": {\"id\": 100}}}"))
			.andExpect(status().isOk())
			.andExpect(content().string(""));
	}

	@Test
	void shouldReturn200AndDispatchOnHappyPath() throws Exception {
		mockMvc.perform(post("/telegram/webhook").header(SECRET_HEADER, "ok")
			.contentType(MediaType.APPLICATION_JSON)
			.content(COMMIT_UPDATE)).andExpect(status().isOk()).andExpect(content().string(""));

		ArgumentCaptor<TelegramUpdate> captor = ArgumentCaptor.forClass(TelegramUpdate.class);
		verify(secretValidator).validate("ok");
		verify(dispatcher).dispatch(captor.capture());
		assertThat(captor.getValue().updateId()).isEqualTo(10L);
		assertThat(captor.getValue().message().text()).isEqualTo("/commit hello");
		assertThat(captor.getValue().message().chat().id()).isEqualTo(100L);
	}

	@Test
	void shouldReturn400WhenBodyIsMalformed() throws Exception {
		mockMvc.perform(post("/telegram/webhook").header(SECRET_HEADER, "ok")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{not json")).andExpect(status().isBadRequest()).andExpect(content().string(""));

		verifyNoInteractions(dispatcher);
	}

}
