package com.luiz.commit_mirror.integration;

public interface TelegramClient {

	void sendMessage(long chatId, String text);

}
