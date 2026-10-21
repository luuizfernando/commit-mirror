package com.luiz.commit_mirror.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import tools.jackson.databind.json.JsonMapper;
import com.luiz.commit_mirror.TestProperties;

class RestClientGitHubContentsGatewayTest {

	private static final String PATH = "logs/2026-10.md";

	private static final String API_PATH = "/repos/owner/repo/contents/" + PATH;

	private static final String TOKEN = "gh-token";

	private static final Instant WHEN = Instant.parse("2026-10-21T15:00:00Z");

	private WireMockServer server;

	private RestClientGitHubContentsGateway gateway;

	@BeforeEach
	void startServer() {
		server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
		server.start();
		RestClient restClient = RestClient.builder()
			.baseUrl(server.baseUrl())
			.requestFactory(new JdkClientHttpRequestFactory(
					HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()))
			.build();
		gateway = new RestClientGitHubContentsGateway(restClient, TestProperties.defaults());
	}

	@AfterEach
	void stopServer() {
		server.stop();
	}

	private static String base64(String text) {
		return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
	}

	private void stubExistingFile(String text) {
		server.stubFor(get(urlPathEqualTo(API_PATH)).willReturn(aResponse().withStatus(200)
			.withHeader("Content-Type", "application/json")
			.withBody("{\"sha\":\"file-sha\",\"encoding\":\"base64\",\"content\":\"" + base64(text) + "\"}")));
	}

	private void stubMissingFile() {
		server.stubFor(get(urlPathEqualTo(API_PATH)).willReturn(aResponse().withStatus(404)
			.withHeader("Content-Type", "application/json")
			.withBody("{\"message\":\"Not Found\"}")));
	}

	private void stubPutOk() {
		server.stubFor(put(urlPathEqualTo(API_PATH)).willReturn(aResponse().withStatus(201)
			.withHeader("Content-Type", "application/json")
			.withBody("{\"commit\":{\"sha\":\"commit-sha-1\"},\"content\":{\"sha\":\"new-file-sha\"}}")));
	}

	private String putBodyText() {
		LoggedRequest request = server.findAll(putRequestedFor(urlPathEqualTo(API_PATH))).get(0);
		String content = JsonMapper.builder().build().readTree(request.getBodyAsString()).get("content").asString();
		return new String(Base64.getDecoder().decode(content), StandardCharsets.UTF_8);
	}

	@Test
	void shouldCreateFileWhenMonthFileDoesNotExist() {
		stubMissingFile();
		stubPutOk();

		gateway.appendLine(PATH, "Commit 1", "Commit 1", WHEN);

		assertThat(putBodyText()).isEqualTo("Commit 1\n");
		server.verify(putRequestedFor(urlPathEqualTo(API_PATH)).withRequestBody(matchingJsonPath("$[?(!@.sha)]")));
	}

	@Test
	void shouldAppendLineKeepingPreviousContent() {
		stubExistingFile("Commit 1\nCommit 2\n");
		stubPutOk();

		gateway.appendLine(PATH, "Commit 3", "Commit 3", WHEN);

		assertThat(putBodyText()).isEqualTo("Commit 1\nCommit 2\nCommit 3\n");
	}

	@Test
	void shouldDecodeBase64ContentWithLineBreaks() {
		String encoded = base64("Commit 1\n").replace("Q29t", "Q29t\\n");
		server.stubFor(get(urlPathEqualTo(API_PATH)).willReturn(aResponse().withStatus(200)
			.withHeader("Content-Type", "application/json")
			.withBody("{\"sha\":\"file-sha\",\"content\":\"" + encoded + "\"}")));
		stubPutOk();

		gateway.appendLine(PATH, "Commit 2", "Commit 2", WHEN);

		assertThat(putBodyText()).isEqualTo("Commit 1\nCommit 2\n");
	}

	@Test
	void shouldAddTrailingNewlineWhenPreviousContentLacksIt() {
		stubExistingFile("Commit 1");
		stubPutOk();

		gateway.appendLine(PATH, "Commit 2", "Commit 2", WHEN);

		assertThat(putBodyText()).isEqualTo("Commit 1\nCommit 2\n");
	}

	@Test
	void shouldSendCurrentShaWhenUpdatingFile() {
		stubExistingFile("Commit 1\n");
		stubPutOk();

		gateway.appendLine(PATH, "Commit 2", "Commit 2", WHEN);

		server.verify(putRequestedFor(urlPathEqualTo(API_PATH)).withRequestBody(matchingJsonPath("$.sha", equalTo("file-sha"))));
	}

	@Test
	void shouldSendAuthorAndCommitterWithExplicitDates() {
		stubMissingFile();
		stubPutOk();

		gateway.appendLine(PATH, "Commit 1", "Commit 1", WHEN);

		String person = "{\"name\":\"Author Name\",\"email\":\"author@example.com\",\"date\":\"2026-10-21T15:00:00Z\"}";
		server.verify(putRequestedFor(urlPathEqualTo(API_PATH))
			.withRequestBody(matchingJsonPath("$.author", equalToJson(person)))
			.withRequestBody(matchingJsonPath("$.committer", equalToJson(person))));
	}

	@Test
	void shouldSendConfiguredBranchAndCommitMessage() {
		stubMissingFile();
		stubPutOk();

		gateway.appendLine(PATH, "Commit 1", "Commit 1", WHEN);

		server.verify(putRequestedFor(urlPathEqualTo(API_PATH))
			.withRequestBody(matchingJsonPath("$.branch", equalTo("main")))
			.withRequestBody(matchingJsonPath("$.message", equalTo("Commit 1"))));
		server.verify(getRequestedFor(urlPathEqualTo(API_PATH)).withQueryParam("ref", equalTo("main")));
	}

	@Test
	void shouldSendBearerToken() {
		stubMissingFile();
		stubPutOk();

		gateway.appendLine(PATH, "Commit 1", "Commit 1", WHEN);

		server.verify(getRequestedFor(urlPathEqualTo(API_PATH)).withHeader("Authorization", equalTo("Bearer " + TOKEN)));
		server.verify(putRequestedFor(urlPathEqualTo(API_PATH)).withHeader("Authorization", equalTo("Bearer " + TOKEN)));
	}

	@Test
	void shouldReturnCommitShaFromResponse() {
		stubMissingFile();
		stubPutOk();

		assertThat(gateway.appendLine(PATH, "Commit 1", "Commit 1", WHEN)).isEqualTo("commit-sha-1");
	}

	@Test
	void shouldNotAppendWhenLineAlreadyPresent() {
		stubExistingFile("Commit 1\nCommit 10\n");

		String sha = gateway.appendLine(PATH, "Commit 1", "Commit 1", WHEN);

		assertThat(sha).isNull();
		assertThat(server.findAll(putRequestedFor(urlPathEqualTo(API_PATH)))).isEmpty();
	}

	@Test
	void shouldNotTreatPrefixOfAnotherLineAsPresent() {
		stubExistingFile("Commit 10\n");
		stubPutOk();

		gateway.appendLine(PATH, "Commit 1", "Commit 1", WHEN);

		assertThat(putBodyText()).isEqualTo("Commit 10\nCommit 1\n");
	}

	@Test
	void shouldMapConflictToGitHubConflictException() {
		stubExistingFile("Commit 1\n");
		server.stubFor(put(urlPathEqualTo(API_PATH)).willReturn(aResponse().withStatus(409)));

		assertThatThrownBy(() -> gateway.appendLine(PATH, "Commit 2", "Commit 2", WHEN))
			.isInstanceOf(GitHubConflictException.class);
	}

	@Test
	void shouldMapServerErrorToTransientException() {
		server.stubFor(get(urlPathEqualTo(API_PATH)).willReturn(aResponse().withStatus(503)));

		assertThatThrownBy(() -> gateway.appendLine(PATH, "Commit 1", "Commit 1", WHEN))
			.isInstanceOf(GitHubTransientException.class);
	}

	@Test
	void shouldMapUnauthorizedToPermanentException() {
		server.stubFor(get(urlPathEqualTo(API_PATH)).willReturn(aResponse().withStatus(401)));

		assertThatThrownBy(() -> gateway.appendLine(PATH, "Commit 1", "Commit 1", WHEN))
			.isInstanceOf(GitHubPermanentException.class);
	}

	@Test
	void shouldMapConnectionFailureToTransientException() {
		server.stop();

		assertThatThrownBy(() -> gateway.appendLine(PATH, "Commit 1", "Commit 1", WHEN))
			.isInstanceOf(GitHubTransientException.class);
	}

	@Test
	void shouldNotLeakTokenInExceptionMessage() {
		server.stubFor(get(urlPathEqualTo(API_PATH)).willReturn(aResponse().withStatus(401)));
		assertThatThrownBy(() -> gateway.appendLine(PATH, "Commit 1", "Commit 1", WHEN))
			.hasMessageNotContaining(TOKEN)
			.hasNoCause();

		server.stop();
		assertThatThrownBy(() -> gateway.appendLine(PATH, "Commit 1", "Commit 1", WHEN))
			.hasMessageNotContaining(TOKEN)
			.hasNoCause();
	}

}
