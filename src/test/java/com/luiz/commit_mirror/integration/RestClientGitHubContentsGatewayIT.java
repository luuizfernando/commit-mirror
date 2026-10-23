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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.luiz.commit_mirror.TestProperties;

import tools.jackson.databind.json.JsonMapper;

class RestClientGitHubContentsGatewayIT {

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

	private void stubGetBody(String json) {
		server.stubFor(get(urlPathEqualTo(API_PATH))
			.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(json)));
	}

	private void stubExistingFile(String text) {
		stubGetBody("{\"sha\":\"file-sha\",\"encoding\":\"base64\",\"content\":\"" + base64(text) + "\"}");
	}

	private void stubMissingFile() {
		server.stubFor(get(urlPathEqualTo(API_PATH)).willReturn(aResponse().withStatus(404)
			.withHeader("Content-Type", "application/json")
			.withBody("{\"message\":\"Not Found\"}")));
	}

	private void stubPutBody(String json) {
		server.stubFor(put(urlPathEqualTo(API_PATH))
			.willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json").withBody(json)));
	}

	private void stubPutOk() {
		stubPutBody("{\"commit\":{\"sha\":\"commit-sha-1\"},\"content\":{\"sha\":\"new-file-sha\"}}");
	}

	private void stubStatus(MappingBuilder request, int status) {
		server.stubFor(request.willReturn(aResponse().withStatus(status)));
	}

	private void stubNewFileWrite() {
		stubMissingFile();
		stubPutOk();
	}

	private String appendCommitOne() {
		return gateway.appendLine(PATH, "Commit 1", "Commit 1", WHEN);
	}

	private String putBodyText() {
		LoggedRequest request = server.findAll(putRequestedFor(urlPathEqualTo(API_PATH))).get(0);
		String content = JsonMapper.builder().build().readTree(request.getBodyAsString()).get("content").asString();
		return new String(Base64.getDecoder().decode(content), StandardCharsets.UTF_8);
	}

	private static RequestPatternBuilder putRequest() {
		return putRequestedFor(urlPathEqualTo(API_PATH));
	}

	private static String person(String date) {
		return "{\"name\":\"Author Name\",\"email\":\"author@example.com\",\"date\":\"" + date + "\"}";
	}

	// file content

	@Test
	void shouldCreateFileWithTheLineWhenMonthFileDoesNotExist() {
		stubNewFileWrite();

		appendCommitOne();

		assertThat(putBodyText()).isEqualTo("Commit 1\n");
	}

	@Test
	void shouldNotSendShaWhenCreatingTheFile() {
		stubNewFileWrite();

		appendCommitOne();

		server.verify(putRequest().withRequestBody(matchingJsonPath("$[?(!@.sha)]")));
	}

	@Test
	void shouldAppendLineKeepingPreviousContent() {
		stubExistingFile("Commit 1\nCommit 2\n");
		stubPutOk();

		gateway.appendLine(PATH, "Commit 3", "Commit 3", WHEN);

		assertThat(putBodyText()).isEqualTo("Commit 1\nCommit 2\nCommit 3\n");
	}

	@Test
	void shouldWriteOnlyTheLineWhenExistingFileIsEmpty() {
		stubExistingFile("");
		stubPutOk();

		appendCommitOne();

		assertThat(putBodyText()).isEqualTo("Commit 1\n");
	}

	@Test
	void shouldDecodeBase64ContentWithLineBreaks() {
		String encoded = base64("Commit 1\n").replace("Q29t", "Q29t\\n");
		stubGetBody("{\"sha\":\"file-sha\",\"content\":\"" + encoded + "\"}");
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

		server.verify(putRequest().withRequestBody(matchingJsonPath("$.sha", equalTo("file-sha"))));
	}

	// idempotency

	@Test
	void shouldReturnNullWhenLineIsAlreadyPresent() {
		stubExistingFile("Commit 1\nCommit 10\n");

		assertThat(appendCommitOne()).isNull();
	}

	@Test
	void shouldNotWriteWhenLineIsAlreadyPresent() {
		stubExistingFile("Commit 1\nCommit 10\n");

		appendCommitOne();

		assertThat(server.findAll(putRequest())).isEmpty();
	}

	@Test
	void shouldTreatLineWithSurroundingSpacesAsPresent() {
		stubExistingFile("  Commit 1  \n");

		appendCommitOne();

		assertThat(server.findAll(putRequest())).isEmpty();
	}

	@Test
	void shouldNotTreatPrefixOfAnotherLineAsPresent() {
		stubExistingFile("Commit 10\n");
		stubPutOk();

		appendCommitOne();

		assertThat(putBodyText()).isEqualTo("Commit 10\nCommit 1\n");
	}

	// commit metadata

	@Test
	void shouldSendAuthorWithExplicitDate() {
		stubNewFileWrite();

		appendCommitOne();

		server.verify(putRequest().withRequestBody(matchingJsonPath("$.author", equalToJson(person("2026-10-21T15:00:00Z")))));
	}

	@Test
	void shouldSendCommitterWithExplicitDate() {
		stubNewFileWrite();

		appendCommitOne();

		server.verify(
				putRequest().withRequestBody(matchingJsonPath("$.committer", equalToJson(person("2026-10-21T15:00:00Z")))));
	}

	@Test
	void shouldTruncateCommitDateToSeconds() {
		stubNewFileWrite();

		gateway.appendLine(PATH, "Commit 1", "Commit 1", Instant.parse("2026-10-21T15:00:00.987654Z"));

		server.verify(putRequest().withRequestBody(matchingJsonPath("$.author.date", equalTo("2026-10-21T15:00:00Z"))));
	}

	@Test
	void shouldSendConfiguredBranchOnWrite() {
		stubNewFileWrite();

		appendCommitOne();

		server.verify(putRequest().withRequestBody(matchingJsonPath("$.branch", equalTo("main"))));
	}

	@Test
	void shouldReadFromConfiguredBranch() {
		stubNewFileWrite();

		appendCommitOne();

		server.verify(getRequestedFor(urlPathEqualTo(API_PATH)).withQueryParam("ref", equalTo("main")));
	}

	@Test
	void shouldSendTheGivenCommitMessage() {
		stubNewFileWrite();

		gateway.appendLine(PATH, "Commit 1", "Commit message 1", WHEN);

		server.verify(putRequest().withRequestBody(matchingJsonPath("$.message", equalTo("Commit message 1"))));
	}

	@Test
	void shouldSendBearerTokenOnRead() {
		stubNewFileWrite();

		appendCommitOne();

		server.verify(getRequestedFor(urlPathEqualTo(API_PATH)).withHeader("Authorization", equalTo("Bearer " + TOKEN)));
	}

	@Test
	void shouldSendBearerTokenOnWrite() {
		stubNewFileWrite();

		appendCommitOne();

		server.verify(putRequest().withHeader("Authorization", equalTo("Bearer " + TOKEN)));
	}

	@Test
	void shouldReturnCommitShaFromResponse() {
		stubNewFileWrite();

		assertThat(appendCommitOne()).isEqualTo("commit-sha-1");
	}

	// error handling

	@Test
	void shouldMapConflictOnWriteToGitHubConflictException() {
		stubExistingFile("Commit 1\n");
		stubStatus(put(urlPathEqualTo(API_PATH)), 409);

		assertThatThrownBy(() -> gateway.appendLine(PATH, "Commit 2", "Commit 2", WHEN))
			.isInstanceOf(GitHubConflictException.class);
	}

	@ParameterizedTest
	@ValueSource(ints = { 408, 429, 500, 502, 503 })
	void shouldMapRetryableStatusOnReadToTransientException(int status) {
		stubStatus(get(urlPathEqualTo(API_PATH)), status);

		assertThatThrownBy(this::appendCommitOne).isInstanceOf(GitHubTransientException.class);
	}

	@ParameterizedTest
	@ValueSource(ints = { 400, 401, 403, 422 })
	void shouldMapRejectedRequestOnReadToPermanentException(int status) {
		stubStatus(get(urlPathEqualTo(API_PATH)), status);

		assertThatThrownBy(this::appendCommitOne).isInstanceOf(GitHubPermanentException.class);
	}

	@Test
	void shouldMapServerErrorOnWriteToTransientException() {
		stubMissingFile();
		stubStatus(put(urlPathEqualTo(API_PATH)), 502);

		assertThatThrownBy(this::appendCommitOne).isInstanceOf(GitHubTransientException.class);
	}

	@Test
	void shouldMapRejectedWriteToPermanentException() {
		stubMissingFile();
		stubStatus(put(urlPathEqualTo(API_PATH)), 422);

		assertThatThrownBy(this::appendCommitOne).isInstanceOf(GitHubPermanentException.class);
	}

	@Test
	void shouldMapWriteResponseWithoutCommitToTransientException() {
		stubMissingFile();
		stubPutBody("{\"content\":{\"sha\":\"new-file-sha\"}}");

		assertThatThrownBy(this::appendCommitOne).isInstanceOf(GitHubTransientException.class);
	}

	@Test
	void shouldMapConnectionFailureToTransientException() {
		server.stop();

		assertThatThrownBy(this::appendCommitOne).isInstanceOf(GitHubTransientException.class);
	}

	// secret protection

	@Test
	void shouldNotLeakTokenWhenGitHubRejectsTheRequest() {
		stubStatus(get(urlPathEqualTo(API_PATH)), 401);

		assertThatThrownBy(this::appendCommitOne).hasMessageNotContaining(TOKEN).hasNoCause();
	}

	@Test
	void shouldNotLeakTokenWhenConnectionFails() {
		server.stop();

		assertThatThrownBy(this::appendCommitOne).hasMessageNotContaining(TOKEN).hasNoCause();
	}

}
