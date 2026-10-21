package com.luiz.commit_mirror.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.springframework.stereotype.Service;

import com.luiz.commit_mirror.config.AppProperties;
import com.luiz.commit_mirror.exception.InvalidWebhookSecretException;

@Service
public class WebhookSecretValidator {

	private final byte[] expectedDigest;

	public WebhookSecretValidator(AppProperties properties) {
		this.expectedDigest = sha256(properties.telegram().webhookSecret());
	}

	public void validate(String receivedSecret) {
		if (receivedSecret == null || !MessageDigest.isEqual(expectedDigest, sha256(receivedSecret))) {
			throw new InvalidWebhookSecretException();
		}
	}

	private static byte[] sha256(String value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is not available", ex);
		}
	}

}
