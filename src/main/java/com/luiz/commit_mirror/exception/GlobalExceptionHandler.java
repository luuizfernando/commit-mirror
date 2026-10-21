package com.luiz.commit_mirror.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(InvalidWebhookSecretException.class)
	ResponseEntity<Void> handleInvalidSecret() {
		return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<Void> handleUnreadableBody() {
		return ResponseEntity.badRequest().build();
	}

}
