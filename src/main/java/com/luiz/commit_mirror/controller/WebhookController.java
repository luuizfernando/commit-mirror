package com.luiz.commit_mirror.controller;

import org.springframework.web.bind.annotation.*;

import com.luiz.commit_mirror.dto.TelegramUpdate;
import com.luiz.commit_mirror.service.UpdateDispatcher;

/** The secret header is checked earlier, by {@link WebhookSecretInterceptor}. */
@RestController
@RequestMapping("/telegram/webhook")
public class WebhookController {

	private final UpdateDispatcher dispatcher;

	public WebhookController(UpdateDispatcher dispatcher) {
		this.dispatcher = dispatcher;
	}

	@PostMapping
	public void receive(@RequestBody TelegramUpdate update) {
		dispatcher.dispatch(update);
	}

}
