package com.luiz.commit_mirror.service;

import org.springframework.stereotype.Service;

@Service
public class LogLineFormatter {

	public String format(long sequenceNumber) {
		return "Commit " + sequenceNumber;
	}

}
