package com.luiz.commit_mirror.support;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class FakeTelegramServer {

	public record Sent(long chatId, String text) {
	}

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final List<Sent> sent = new CopyOnWriteArrayList<>();

	private volatile boolean failing;

	private HttpServer server;

	public FakeTelegramServer start(int port) {
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
		sent.clear();
		failing = false;
	}

	public void failing(boolean failing) {
		this.failing = failing;
	}

	public List<Sent> sent() {
		return List.copyOf(sent);
	}

	private void handle(HttpExchange exchange) throws IOException {
		String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		int status = 200;
		String response = "{\"ok\":true}";
		if (failing) {
			status = 500;
			response = "{\"ok\":false}";
		}
		else if (exchange.getRequestURI().getPath().endsWith("/sendMessage")) {
			recordSentMessage(JSON.readTree(body));
		}
		byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}

	private void recordSentMessage(JsonNode message) {
		JsonNode chatId = message.get("chat_id");
		JsonNode text = message.get("text");
		if (chatId != null && text != null) {
			sent.add(new Sent(chatId.asLong(), text.asString()));
		}
	}

}
