package com.luiz.commit_mirror.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CommandParserTest {

	private final CommandParser parser = new CommandParser();

	private ParsedCommand parse(String text) {
		return parser.parse(text).orElseThrow();
	}

	@Test
	void shouldParseCommandNameWithoutLeadingSlash() {
		assertThat(parse("/commit fix the bug").name()).isEqualTo("commit");
	}

	@Test
	void shouldParseEverythingAfterTheCommandAsArguments() {
		assertThat(parse("/commit fix the bug").arguments()).isEqualTo("fix the bug");
	}

	@Test
	void shouldTrimSurroundingWhitespaceFromArguments() {
		assertThat(parse("  /commit    spaced out   ").arguments()).isEqualTo("spaced out");
	}

	@Test
	void shouldKeepLineBreaksInsideArguments() {
		assertThat(parse("/commit first line\nsecond line").arguments()).isEqualTo("first line\nsecond line");
	}

	@Test
	void shouldParseCommandNameWhenNoArgumentsAreSent() {
		assertThat(parse("/commit").name()).isEqualTo("commit");
	}

	@Test
	void shouldReturnEmptyArgumentsWhenNoArgumentsAreSent() {
		assertThat(parse("/commit").arguments()).isEmpty();
	}

	@Test
	void shouldStripBotSuffixFromCommandName() {
		assertThat(parse("/commit@MyBot hello").name()).isEqualTo("commit");
	}

	@Test
	void shouldKeepArgumentsWhenCommandHasBotSuffix() {
		assertThat(parse("/commit@MyBot hello").arguments()).isEqualTo("hello");
	}

	@Test
	void shouldReturnEmptyWhenTextIsNotACommand() {
		assertThat(parser.parse("just chatting")).isEmpty();
	}

	@Test
	void shouldReturnEmptyWhenSlashIsNotTheFirstCharacter() {
		assertThat(parser.parse("hello /commit")).isEmpty();
	}

	@Test
	void shouldReturnEmptyWhenTextIsEmpty() {
		assertThat(parser.parse("")).isEmpty();
	}

	@Test
	void shouldReturnEmptyWhenTextIsBlank() {
		assertThat(parser.parse("   ")).isEmpty();
	}

}
