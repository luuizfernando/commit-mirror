package com.luiz.commit_mirror.service;

import java.util.Optional;

import org.springframework.stereotype.Service;

@Service
public class CommandParser {

	public Optional<ParsedCommand> parse(String text) {
		String trimmed = text.strip();
		if (!trimmed.startsWith("/")) {
			return Optional.empty();
		}
		
		String[] parts = trimmed.substring(1).split("\s+", 2);
		String name = stripBotSuffix(parts[0]);
		String arguments = parts.length > 1 ? parts[1].strip() : "";
		return Optional.of(new ParsedCommand(name, arguments));
	}

	private static String stripBotSuffix(String name) {
		int at = name.indexOf('@');
		return at >= 0 ? name.substring(0, at) : name;
	}

}
