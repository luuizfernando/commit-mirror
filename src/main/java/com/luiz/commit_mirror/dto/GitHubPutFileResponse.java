package com.luiz.commit_mirror.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubPutFileResponse(CommitRef commit) {

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record CommitRef(String sha) {
	}

}
