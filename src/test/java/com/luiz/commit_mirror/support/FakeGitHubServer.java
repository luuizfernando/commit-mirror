package com.luiz.commit_mirror.support;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Minimal stateful stand-in for the GitHub Contents API (GET/PUT of a file). It enforces the {@code sha} contract
 * like the real API does (409 when stale), records every commit, and can be switched to fail on demand. Dependency
 * free on purpose, so it also runs standalone for manual tests.
 */
public final class FakeGitHubServer {

	public record Commit(String path, String message, String authorName, String authorEmail, String authorDate,
			String committerDate, String branch, String authorization, String rawBody) {
	}

	private record FileState(String content, String sha) {
	}

	private static final Pattern PATH = Pattern.compile("^/repos/[^/]+/[^/]+/contents/(.+)$");

	private final Map<String, FileState> files = new ConcurrentHashMap<>();

	private final List<Commit> commits = new CopyOnWriteArrayList<>();

	private volatile int failWithStatus;

	private HttpServer server;

	public FakeGitHubServer start(int port) {
		try {
			server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
		server.createContext("/", this::handle);
		server.start();
		return this;
	}

	public void stop() {
		server.stop(0);
	}

	public String baseUrl() {
		return "http://localhost:" + server.getAddress().getPort();
	}

	public void reset() {
		files.clear();
		commits.clear();
		failWithStatus = 0;
	}

	/** Every request answers with this status until {@link #recover()} (0 disables). */
	public void failWith(int status) {
		this.failWithStatus = status;
	}

	public void recover() {
		this.failWithStatus = 0;
	}

	public String fileContent(String path) {
		FileState state = files.get(path);
		return state == null ? null : state.content();
	}

	public List<Commit> commits() {
		return new ArrayList<>(commits);
	}

	private synchronized void handle(HttpExchange exchange) throws IOException {
		String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		Matcher matcher = PATH.matcher(exchange.getRequestURI().getPath());
		if (failWithStatus > 0) {
			respond(exchange, failWithStatus, "{\"message\":\"simulated failure\"}");
		}
		else if (!matcher.matches()) {
			respond(exchange, 404, "{\"message\":\"Not Found\"}");
		}
		else if ("GET".equals(exchange.getRequestMethod())) {
			get(exchange, matcher.group(1));
		}
		else if ("PUT".equals(exchange.getRequestMethod())) {
			put(exchange, matcher.group(1), body);
		}
		else {
			respond(exchange, 405, "{}");
		}
	}

	private void get(HttpExchange exchange, String path) throws IOException {
		FileState state = files.get(path);
		if (state == null) {
			respond(exchange, 404, "{\"message\":\"Not Found\"}");
			return;
		}
		String encoded = Base64.getMimeEncoder(60, "\n".getBytes(StandardCharsets.UTF_8))
			.encodeToString(state.content().getBytes(StandardCharsets.UTF_8));
		respond(exchange, 200, "{\"sha\":\"" + state.sha() + "\",\"encoding\":\"base64\",\"content\":\""
				+ encoded.replace("\n", "\\n") + "\"}");
	}

	private void put(HttpExchange exchange, String path, String body) throws IOException {
		String sha = find(body, "\"sha\"\\s*:\\s*\"([^\"]*)\"");
		FileState current = files.get(path);
		if (current != null && sha == null) {
			respond(exchange, 422, "{\"message\":\"sha wasn't supplied\"}");
			return;
		}
		if (current != null && !current.sha().equals(sha)) {
			respond(exchange, 409, "{\"message\":\"does not match\"}");
			return;
		}
		if (current == null && sha != null) {
			respond(exchange, 422, "{\"message\":\"sha supplied for a missing file\"}");
			return;
		}
		String content = new String(Base64.getDecoder().decode(find(body, "\"content\"\\s*:\\s*\"([^\"]*)\"")),
				StandardCharsets.UTF_8);
		files.put(path, new FileState(content, UUID.randomUUID().toString()));
		String commitSha = UUID.randomUUID().toString().replace("-", "") + "00000000";
		commits.add(new Commit(path, find(body, "\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\""),
				find(body, "\"author\"\\s*:\\s*\\{[^}]*\"name\"\\s*:\\s*\"([^\"]*)\""),
				find(body, "\"author\"\\s*:\\s*\\{[^}]*\"email\"\\s*:\\s*\"([^\"]*)\""),
				find(body, "\"author\"\\s*:\\s*\\{[^}]*\"date\"\\s*:\\s*\"([^\"]*)\""),
				find(body, "\"committer\"\\s*:\\s*\\{[^}]*\"date\"\\s*:\\s*\"([^\"]*)\""),
				find(body, "\"branch\"\\s*:\\s*\"([^\"]*)\""), exchange.getRequestHeaders().getFirst("Authorization"),
				body));
		respond(exchange, current == null ? 201 : 200, "{\"commit\":{\"sha\":\"" + commitSha
				+ "\"},\"content\":{\"sha\":\"" + files.get(path).sha() + "\"}}");
	}

	private static String find(String text, String regex) {
		Matcher matcher = Pattern.compile(regex, Pattern.DOTALL).matcher(text);
		return matcher.find() ? matcher.group(1) : null;
	}

	private static void respond(HttpExchange exchange, int status, String json) throws IOException {
		byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}

}
