package com.luiz.commit_mirror.controller;

import org.springframework.web.bind.annotation.*;

import com.luiz.commit_mirror.dto.TelegramUpdate;
import com.luiz.commit_mirror.service.UpdateDispatcher;

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
