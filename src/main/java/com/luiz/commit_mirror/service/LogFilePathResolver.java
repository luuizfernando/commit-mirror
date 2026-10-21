package com.luiz.commit_mirror.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Service;

import com.luiz.commit_mirror.config.AppProperties;

@Service
public class LogFilePathResolver {

	private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

	private final String directory;

	public LogFilePathResolver(AppProperties properties) {
		this.directory = properties.github().logsDirectory();
	}

	public String resolve(LocalDate commitDate) {
		return directory + "/" + MONTH.format(commitDate) + ".md";
	}

}
