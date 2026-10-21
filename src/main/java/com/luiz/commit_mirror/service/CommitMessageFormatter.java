package com.luiz.commit_mirror.service;

import org.springframework.stereotype.Service;

/** Builds the Git commit message: only the sequence, never the typed text or a date. */
@Service
public class CommitMessageFormatter {

	public String format(long sequenceNumber) {
		return "Commit " + sequenceNumber;
	}

}
