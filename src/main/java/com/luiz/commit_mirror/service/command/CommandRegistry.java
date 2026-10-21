package com.luiz.commit_mirror.service.command;

import java.util.*;

import org.springframework.stereotype.Component;

@Component
public class CommandRegistry {

	private final Map<String, BotCommand> commandsByName = new HashMap<>();

	public CommandRegistry(List<BotCommand> commands) {
		for (BotCommand command : commands) {
			if (commandsByName.putIfAbsent(command.name(), command) != null) {
				throw new IllegalStateException("Duplicate bot command name: " + command.name());
			}
		}
	}

	public Optional<BotCommand> find(String name) {
		return Optional.ofNullable(commandsByName.get(name));
	}

}
