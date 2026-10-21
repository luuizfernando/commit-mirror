package com.luiz.commit_mirror.integration;

import java.time.Instant;

public interface GitHubContentsGateway {

	/**
	 * Appends {@code line} to the file at {@code path} (creating it when missing) as a single commit.
	 * @param timestamp used as author and committer date, so the commit lands on the right day of the graph
	 * @return the new commit SHA, or {@code null} when the line was already in the file and nothing was written
	 */
	String appendLine(String path, String line, String commitMessage, Instant timestamp);

}
