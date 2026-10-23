package com.luiz.commit_mirror.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class CommandRegistryTest {

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

	@Test
	void shouldResolveCommandByNameAmongSeveral() {
		BotCommand ping = stubCommand("ping");
		CommandRegistry registry = new CommandRegistry(List.of(stubCommand("commit"), ping));

		assertThat(registry.find("ping")).containsSame(ping);
	}

	@Test
	void shouldReturnEmptyForUnknownCommand() {
		CommandRegistry registry = new CommandRegistry(List.of(stubCommand("commit")));

		assertThat(registry.find("nope")).isEmpty();
	}

	@Test
	void shouldReturnEmptyWhenNoCommandIsRegistered() {
		assertThat(new CommandRegistry(List.of()).find("commit")).isEmpty();
	}

	@Test
	void shouldFailNamingTheDuplicateWhenTwoCommandsShareTheSameName() {
		List<BotCommand> commands = List.of(stubCommand("commit"), stubCommand("commit"));

		assertThatThrownBy(() -> new CommandRegistry(commands)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("commit");
	}

}
