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

class RestClientTelegramClientTest {

	private static final String BOT_TOKEN = "123456:SECRET-BOT-TOKEN";

	private WireMockServer server;

	private RestClientTelegramClient client;

	@BeforeEach
	void startServer() {
		server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
		server.start();
		RestClient restClient = RestClient.builder()
			.baseUrl(server.baseUrl())
			.requestFactory(new JdkClientHttpRequestFactory(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()))
			.build();
		client = new RestClientTelegramClient(restClient,
				TestProperties.withTelegram(new AppProperties.Telegram(BOT_TOKEN, "secret", 42L, server.baseUrl())));
	}

	@AfterEach
	void stopServer() {
		server.stop();
	}

	@Test
	void shouldPostSendMessageWithChatIdAndText() {
		server.stubFor(post(urlEqualTo("/bot" + BOT_TOKEN + "/sendMessage")).willReturn(aResponse().withStatus(200)
			.withHeader("Content-Type", "application/json")
			.withBody("{\"ok\":true}")));

		client.sendMessage(100L, "hello");

		server.verify(postRequestedFor(urlEqualTo("/bot" + BOT_TOKEN + "/sendMessage"))
			.withRequestBody(equalToJson("{\"chat_id\":100,\"text\":\"hello\"}")));
	}

	@Test
	void shouldThrowTelegramApiExceptionOnServerError() {
		server.stubFor(post(urlEqualTo("/bot" + BOT_TOKEN + "/sendMessage")).willReturn(aResponse().withStatus(500)));

		assertThatThrownBy(() -> client.sendMessage(100L, "hello")).isInstanceOf(TelegramApiException.class);
	}

	@Test
	void shouldNotLeakBotTokenInExceptionMessage() {
		server.stubFor(post(urlEqualTo("/bot" + BOT_TOKEN + "/sendMessage")).willReturn(aResponse().withStatus(500)));
		assertThatThrownBy(() -> client.sendMessage(100L, "hello")).isInstanceOf(TelegramApiException.class)
			.hasMessageNotContaining(BOT_TOKEN)
			.hasNoCause();

		server.stop();
		assertThatThrownBy(() -> client.sendMessage(100L, "hello")).isInstanceOf(TelegramApiException.class)
			.hasMessageNotContaining(BOT_TOKEN)
			.hasNoCause();
	}

}
