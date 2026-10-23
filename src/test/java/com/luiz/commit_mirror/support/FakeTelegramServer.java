package com.luiz.commit_mirror.support;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

public final class FakeTelegramServer {

	public record Sent(long chatId, String text) {
	}

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
			Matcher chat = Pattern.compile("\"chat_id\"\\s*:\\s*(-?\\d+)").matcher(body);
			Matcher text = Pattern.compile("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(body);
			if (chat.find() && text.find()) {
				sent.add(new Sent(Long.parseLong(chat.group(1)), text.group(1)));
			}
		}
		byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}

}
