package com.luiz.commit_mirror.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class CommandRegistryTest {

	@Test
	void shouldResolveCommandByName() {
		BotCommand commit = stubCommand("commit");
		CommandRegistry registry = new CommandRegistry(List.of(commit, stubCommand("other")));

		assertThat(registry.find("commit")).containsSame(commit);
	}

	@Test
	void shouldReturnEmptyForUnknownCommand() {
		CommandRegistry registry = new CommandRegistry(List.of(stubCommand("commit")));

		assertThat(registry.find("nope")).isEmpty();
	}

	@Test
	void shouldFailWhenTwoCommandsShareTheSameName() {
		List<BotCommand> commands = List.of(stubCommand("commit"), stubCommand("commit"));

		assertThatThrownBy(() -> new CommandRegistry(commands)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("commit");
	}

	@Test
	void shouldResolveNewCommandWithoutChangingTheRegistry() {
		BotCommand ping = stubCommand("ping");

		assertThat(new CommandRegistry(List.of(stubCommand("commit"), ping)).find("ping")).containsSame(ping);
	}

	private static BotCommand stubCommand(String name) {
		return new BotCommand() {
			@Override
			public String name() {
				return name;
			}

			@Override
			public void execute(CommandContext context) {
			}
		};
	}

}
