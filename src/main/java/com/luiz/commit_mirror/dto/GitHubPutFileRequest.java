package com.luiz.commit_mirror.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record GitHubPutFileRequest(String message, String content, String sha, String branch, GitHubPerson committer,
		GitHubPerson author) {

	public record GitHubPerson(String name, String email, String date) {
	}

}
