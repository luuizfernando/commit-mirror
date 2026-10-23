package com.luiz.commit_mirror.controller;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.luiz.commit_mirror.service.WebhookSecretValidator;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Checks the webhook secret before the body is read, so an unauthenticated request always gets 403, even when its
 * body is malformed.
 */
@Component
public class WebhookSecretInterceptor implements HandlerInterceptor {

	static final String SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token";

	private final WebhookSecretValidator secretValidator;

	public WebhookSecretInterceptor(WebhookSecretValidator secretValidator) {
		this.secretValidator = secretValidator;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		secretValidator.validate(request.getHeader(SECRET_HEADER));
		return true;
	}

}
