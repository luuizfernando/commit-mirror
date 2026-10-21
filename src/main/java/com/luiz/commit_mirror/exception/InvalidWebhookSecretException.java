package com.luiz.commit_mirror.exception;

public class InvalidWebhookSecretException extends RuntimeException {

	public InvalidWebhookSecretException() {
		super("Invalid webhook secret");
	}

}
