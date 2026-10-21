package com.luiz.commit_mirror.integration;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.luiz.commit_mirror.config.AppProperties;
import com.luiz.commit_mirror.dto.GitHubFileContent;
import com.luiz.commit_mirror.dto.GitHubPutFileRequest;
import com.luiz.commit_mirror.dto.GitHubPutFileRequest.GitHubPerson;
import com.luiz.commit_mirror.dto.GitHubPutFileResponse;

@Component
public class RestClientGitHubContentsGateway implements GitHubContentsGateway {

	private final RestClient restClient;

	private final AppProperties.GitHub github;

	public RestClientGitHubContentsGateway(@Qualifier("githubRestClient") RestClient restClient,
			AppProperties properties) {
		this.restClient = restClient;
		this.github = properties.github();
	}

	@Override
	public String appendLine(String path, String line, String commitMessage, Instant timestamp) {
		try {
			GitHubFileContent existing = fetch(path);
			String current = existing == null ? "" : decode(existing.content());
			if (containsLine(current, line)) {
				return null;
			}
			String updated = current + (current.isEmpty() || current.endsWith("\n") ? "" : "\n") + line + "\n";
			return put(path, updated, existing == null ? null : existing.sha(), commitMessage, timestamp);
		}
		catch (RestClientException ex) {
			throw new GitHubTransientException("GitHub request failed: " + ex.getClass().getSimpleName());
		}
	}

	private GitHubFileContent fetch(String path) {
		return restClient.get()
			.uri(uri -> uri.path(contentsPath(path)).queryParam("ref", github.branch()).build())
			.headers(this::authorize)
			.exchange((request, response) -> {
				int status = response.getStatusCode().value();
				if (status == 404) {
					return null;
				}
				if (response.getStatusCode().isError()) {
					throw mapError(status);
				}
				return response.bodyTo(GitHubFileContent.class);
			});
	}

	private String put(String path, String content, String sha, String commitMessage, Instant timestamp) {
		GitHubPerson person = new GitHubPerson(github.authorName(), github.authorEmail(),
				timestamp.truncatedTo(ChronoUnit.SECONDS).toString());
		GitHubPutFileRequest body = new GitHubPutFileRequest(commitMessage,
				Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)), sha, github.branch(),
				person, person);
		GitHubPutFileResponse response = restClient.put()
			.uri(uri -> uri.path(contentsPath(path)).build())
			.headers(this::authorize)
			.contentType(MediaType.APPLICATION_JSON)
			.body(body)
			.exchange((request, reply) -> {
				if (reply.getStatusCode().isError()) {
					throw mapError(reply.getStatusCode().value());
				}
				return reply.bodyTo(GitHubPutFileResponse.class);
			});
		if (response == null || response.commit() == null) {
			throw new GitHubTransientException("GitHub response did not contain the commit");
		}
		return response.commit().sha();
	}

	private String contentsPath(String path) {
		return "/repos/" + github.owner() + "/" + github.repo() + "/contents/" + path;
	}

	private void authorize(HttpHeaders headers) {
		headers.setBearerAuth(github.token());
		headers.set(HttpHeaders.ACCEPT, "application/vnd.github+json");
		headers.set("X-GitHub-Api-Version", "2022-11-28");
	}

	private static String decode(String base64Content) {
		return new String(Base64.getMimeDecoder().decode(base64Content), StandardCharsets.UTF_8);
	}

	/** Whole-line comparison, so "Commit 1" never matches "Commit 10". */
	private static boolean containsLine(String content, String line) {
		return content.lines().anyMatch(existing -> existing.strip().equals(line));
	}

	private static RuntimeException mapError(int status) {
		String message = "GitHub API returned status " + status;
		if (status == 409) {
			return new GitHubConflictException(message);
		}
		if (status >= 500 || status == 408 || status == 429) {
			return new GitHubTransientException(message);
		}
		return new GitHubPermanentException(message);
	}

}
