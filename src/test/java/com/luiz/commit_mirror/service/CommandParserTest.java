package com.luiz.commit_mirror.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CommandParserTest {

	private final CommandParser parser = new CommandParser();

	@Test
	void shouldParseCommandNameAndArguments() {
		ParsedCommand command = parser.parse("/commit fix the bug").orElseThrow();

		assertThat(command.name()).isEqualTo("commit");
		assertThat(command.arguments()).isEqualTo("fix the bug");
	}

	@Test
	void shouldTrimArguments() {
		ParsedCommand command = parser.parse("  /commit    spaced out   ").orElseThrow();

		assertThat(command.arguments()).isEqualTo("spaced out");
	}

	@Test
	void shouldReturnEmptyArgumentsWhenOnlyCommandIsSent() {
		ParsedCommand command = parser.parse("/commit").orElseThrow();

		assertThat(command.name()).isEqualTo("commit");
		assertThat(command.arguments()).isEmpty();
	}

	@Test
	void shouldStripBotSuffixFromCommandName() {
		ParsedCommand command = parser.parse("/commit@MyBot hello").orElseThrow();

		assertThat(command.name()).isEqualTo("commit");
		assertThat(command.arguments()).isEqualTo("hello");
	}

	@Test
	void shouldReturnEmptyWhenTextIsNotACommand() {
		assertThat(parser.parse("just chatting")).isEmpty();
	}

}
