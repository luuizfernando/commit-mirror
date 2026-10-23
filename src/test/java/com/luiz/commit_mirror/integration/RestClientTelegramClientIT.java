package com.luiz.commit_mirror.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpClient;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.luiz.commit_mirror.TestProperties;
import com.luiz.commit_mirror.config.AppProperties;

class RestClientTelegramClientIT {

	private static final String BOT_TOKEN = "123456:SECRET-BOT-TOKEN";

	private static final String SEND_MESSAGE_PATH = "/bot" + BOT_TOKEN + "/sendMessage";

	private WireMockServer server;

	private RestClientTelegramClient client;

	@BeforeEach
	void startServer() {
		server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
		server.start();
		RestClient restClient = RestClient.builder()
			.baseUrl(server.baseUrl())
			.requestFactory(new JdkClientHttpRequestFactory(
					HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()))
			.build();
		client = new RestClientTelegramClient(restClient,
				TestProperties.withTelegram(new AppProperties.Telegram(BOT_TOKEN, "secret", 42L, server.baseUrl())));
	}

	@AfterEach
	void stopServer() {
		server.stop();
	}

	private void stubSendMessageStatus(int status) {
		server.stubFor(post(urlEqualTo(SEND_MESSAGE_PATH)).willReturn(aResponse().withStatus(status)
			.withHeader("Content-Type", "application/json")
			.withBody("{\"ok\":" + (status == 200) + "}")));
	}

	private void sendHello() {
		client.sendMessage(100L, "hello");
	}

	// happy path

	@Test
	void shouldPostSendMessageWithChatIdAndText() {
		stubSendMessageStatus(200);

		sendHello();

		server.verify(postRequestedFor(urlEqualTo(SEND_MESSAGE_PATH))
			.withRequestBody(equalToJson("{\"chat_id\":100,\"text\":\"hello\"}")));
	}

	// error handling

	@Test
	void shouldThrowTelegramApiExceptionOnServerError() {
		stubSendMessageStatus(500);

		assertThatThrownBy(this::sendHello).isInstanceOf(TelegramApiException.class);
	}

	@Test
	void shouldThrowTelegramApiExceptionOnClientError() {
		stubSendMessageStatus(400);

		assertThatThrownBy(this::sendHello).isInstanceOf(TelegramApiException.class);
	}

	@Test
	void shouldThrowTelegramApiExceptionWhenConnectionFails() {
		server.stop();

		assertThatThrownBy(this::sendHello).isInstanceOf(TelegramApiException.class);
	}

	// secret protection

	@Test
	void shouldNotLeakBotTokenWhenTelegramReturnsAnError() {
		stubSendMessageStatus(500);

		assertThatThrownBy(this::sendHello).hasMessageNotContaining(BOT_TOKEN).hasNoCause();
	}

	@Test
	void shouldNotLeakBotTokenWhenConnectionFails() {
		server.stop();

		assertThatThrownBy(this::sendHello).hasMessageNotContaining(BOT_TOKEN).hasNoCause();
	}

}
