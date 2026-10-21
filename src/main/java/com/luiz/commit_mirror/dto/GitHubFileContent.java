package com.luiz.commit_mirror.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubFileContent(String sha, String content) {
}
