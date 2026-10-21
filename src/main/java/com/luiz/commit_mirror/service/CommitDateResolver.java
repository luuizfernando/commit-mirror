package com.luiz.commit_mirror.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.stereotype.Service;

import com.luiz.commit_mirror.config.AppProperties;

@Service
public class CommitDateResolver {

	private final ZoneId zone;

	public CommitDateResolver(AppProperties properties) {
		this.zone = ZoneId.of(properties.timeZone());
	}

	public LocalDate resolve(Instant instant) {
		return instant.atZone(zone).toLocalDate();
	}

}
