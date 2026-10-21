package com.luiz.commit_mirror.integration;

/**
 * GitHub 409: the file changed since it was read. The message is sanitized on purpose: no URL, no token and no cause is attached.
 */
public class GitHubConflictException extends RuntimeException {

	public GitHubConflictException(String message) {
		super(message);
	}

}
