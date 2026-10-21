package com.luiz.commit_mirror.service;

import org.springframework.stereotype.Service;

/** Builds the line appended to the monthly log file: only the sequence, never the typed text or a date. */
@Service
public class LogLineFormatter {

	public String format(long sequenceNumber) {
		return "Commit " + sequenceNumber;
	}

}
