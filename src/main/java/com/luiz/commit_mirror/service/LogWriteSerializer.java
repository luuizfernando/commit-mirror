package com.luiz.commit_mirror.service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

/**
 * Single-worker queue: every GitHub read-modify-write of the log file runs here, one at a time and in submission
 * order. It also makes the webhook asynchronous, since submitting returns immediately.
 */
@Service
public class LogWriteSerializer {

	private static final Logger LOGGER = LoggerFactory.getLogger(LogWriteSerializer.class);

	private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "log-write-worker");
		thread.setDaemon(true);
		return thread;
	});

	public CompletableFuture<Void> submit(Runnable task) {
		return CompletableFuture.runAsync(() -> {
			try {
				task.run();
			}
			catch (RuntimeException ex) {
				LOGGER.error("Queued log write task failed", ex);
				throw ex;
			}
		}, executor);
	}

	@PreDestroy
	void shutdown() {
		executor.shutdown();
	}

}
