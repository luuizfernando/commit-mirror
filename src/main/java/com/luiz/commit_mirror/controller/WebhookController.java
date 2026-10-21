package com.luiz.commit_mirror.controller;

import org.springframework.web.bind.annotation.*;

import com.luiz.commit_mirror.dto.TelegramUpdate;
import com.luiz.commit_mirror.service.UpdateDispatcher;
import com.luiz.commit_mirror.service.WebhookSecretValidator;

@RestController
@RequestMapping("/telegram/webhook")
public class WebhookController {

	private static final String SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token";

	private final WebhookSecretValidator secretValidator;

	private final UpdateDispatcher dispatcher;

	public WebhookController(WebhookSecretValidator secretValidator, UpdateDispatcher dispatcher) {
		this.secretValidator = secretValidator;
		this.dispatcher = dispatcher;
	}

	@PostMapping
	public void receive(
		@RequestHeader(name = SECRET_HEADER, required = false) String secret,
		@RequestBody TelegramUpdate update
	) {
		secretValidator.validate(secret);
		dispatcher.dispatch(update);
	}

}
