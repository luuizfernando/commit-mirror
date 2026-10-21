package com.luiz.commit_mirror.service.command;

public interface BotCommand {

	String name();

	void execute(CommandContext context);

}
