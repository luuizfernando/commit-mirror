package com.luiz.commit_mirror.integration;

public class GitHubTransientException extends RuntimeException {

	public GitHubTransientException(String message) {
		super(message);
	}

}
