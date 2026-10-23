package com.luiz.commit_mirror.integration;

import java.time.Instant;

public interface GitHubContentsGateway {

	String appendLineIfAbsent(String path, String line, String commitMessage, Instant timestamp);

}
