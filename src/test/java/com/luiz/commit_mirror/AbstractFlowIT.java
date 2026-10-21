package com.luiz.commit_mirror;

import java.net.http.HttpClient;
import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import com.luiz.commit_mirror.repository.CommitLogRepository;
import com.luiz.commit_mirror.support.FakeGitHubServer;
import com.luiz.commit_mirror.support.FakeTelegramServer;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

/**
 * Whole application against a real Postgres and stateful fakes of GitHub and Telegram. All subclasses share one
 * Spring context (and one container), so state is reset before every test.
 */
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractFlowIT {

	protected static final String SECRET = "test-webhook-secret";

	protected static final long ALLOWED_USER = 42L;

	protected static final long CHAT_ID = 100L;

	protected static final FakeGitHubServer GITHUB = new FakeGitHubServer().start(0);

	protected static final FakeTelegramServer TELEGRAM = new FakeTelegramServer().start(0);

	@DynamicPropertySource
	static void fakeApis(DynamicPropertyRegistry registry) {
		registry.add("app.github.base-url", GITHUB::baseUrl);
		registry.add("app.telegram.base-url", TELEGRAM::baseUrl);
		registry.add("app.resilience.retry-initial-backoff", () -> "10ms");
		registry.add("app.recovery.enabled", () -> "true");
		registry.add("app.recovery.interval", () -> "1h");
		registry.add("app.recovery.min-age", () -> "0s");
	}

	@Value("${local.server.port}")
	private int serverPort;

	@Autowired
	protected CommitLogRepository repository;

	@Autowired
	protected CircuitBreaker githubCircuitBreaker;

	@BeforeEach
	void resetWorld() {
		repository.deleteAll();
		GITHUB.reset();
		TELEGRAM.reset();
		githubCircuitBreaker.reset();
	}

	protected int postUpdate(String secret, long updateId, long userId, String text) {
		String body = """
				{"update_id": %d, "message": {"message_id": 5, "from": {"id": %d},
				 "chat": {"id": %d}, "text": "%s"}}""".formatted(updateId, userId, CHAT_ID, text);
		return RestClient.builder()
			.requestFactory(new JdkClientHttpRequestFactory(
					HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()))
			.build()
			.post()
			.uri("http://localhost:{port}/telegram/webhook", serverPort)
			.header("X-Telegram-Bot-Api-Secret-Token", secret)
			.contentType(MediaType.APPLICATION_JSON)
			.body(body)
			.exchange((request, response) -> response.getStatusCode().value());
	}

	protected int postCommit(long updateId, String text) {
		return postUpdate(SECRET, updateId, ALLOWED_USER, "/commit " + text);
	}

	protected static Duration timeout() {
		return Duration.ofSeconds(15);
	}

}
