package com.luiz.commit_mirror.integration;

/**
 * GitHub temporary failure (5xx, rate limit or I/O). The message is sanitized on purpose: no URL, no token and no cause is attached.
 */
public class GitHubTransientException extends RuntimeException {

	public GitHubTransientException(String message) {
		super(message);
	}

}
