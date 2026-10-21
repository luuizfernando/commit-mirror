package com.luiz.commit_mirror.integration;

/**
 * GitHub rejected request (authentication, permission or invalid data). The message is sanitized on purpose: no URL, no token and no cause is attached.
 */
public class GitHubPermanentException extends RuntimeException {

	public GitHubPermanentException(String message) {
		super(message);
	}

}
