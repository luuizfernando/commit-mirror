package com.luiz.commit_mirror.integration;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.luiz.commit_mirror.config.AppProperties;
import com.luiz.commit_mirror.dto.TelegramSendMessageRequest;

@Component
public class RestClientTelegramClient implements TelegramClient {

	private final RestClient restClient;

	private final String botToken;

	public RestClientTelegramClient(
		@Qualifier("telegramRestClient") RestClient restClient,
		AppProperties properties
	) {
		this.restClient = restClient;
		this.botToken = properties.telegram().botToken();
	}

	@Override
	public void sendMessage(long chatId, String text) {
		try {
			restClient.post()
				.uri(uri -> uri.path("/bot" + botToken + "/sendMessage").build())
				.body(new TelegramSendMessageRequest(chatId, text))
				.retrieve()
				.onStatus(HttpStatusCode::isError, (request, response) -> {
					throw new TelegramApiException("Telegram sendMessage failed with status " + response.getStatusCode().value());
				})
				.toBodilessEntity();
		}
		catch (TelegramApiException e) {
			throw e;
		}
		catch (RestClientException e) {
			throw new TelegramApiException("Telegram sendMessage failed: " + e.getClass().getSimpleName());
		}
	}

}
