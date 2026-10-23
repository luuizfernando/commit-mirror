package com.luiz.commit_mirror.support;

public final class FakeApis {

	private FakeApis() {
	}

	public static void main(String[] args) throws InterruptedException {
		FakeGitHubServer github = new FakeGitHubServer().start(9101);
		FakeTelegramServer telegram = new FakeTelegramServer().start(9102);
		System.out.println("Fake GitHub   : " + github.baseUrl());
		System.out.println("Fake Telegram : " + telegram.baseUrl());
		int seenCommits = 0;
		int seenMessages = 0;
		while (true) {
			Thread.sleep(500);
			for (; seenCommits < github.commits().size(); seenCommits++) {
				FakeGitHubServer.Commit commit = github.commits().get(seenCommits);
				System.out.println("[github] commit '" + commit.message() + "' in " + commit.path() + " author="
						+ commit.authorName() + " <" + commit.authorEmail() + "> date=" + commit.authorDate());
				System.out.println(github.fileContent(commit.path()));
			}
			for (; seenMessages < telegram.sent().size(); seenMessages++) {
				FakeTelegramServer.Sent sent = telegram.sent().get(seenMessages);
				System.out.println("[telegram] to " + sent.chatId() + ": " + sent.text());
			}
		}
	}

}
