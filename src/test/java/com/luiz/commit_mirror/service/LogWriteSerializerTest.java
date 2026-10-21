package com.luiz.commit_mirror.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LogWriteSerializerTest {

	private final LogWriteSerializer serializer = new LogWriteSerializer();

	@AfterEach
	void shutdown() {
		serializer.shutdown();
	}

	@Test
	void shouldRunSubmissionsOneAtATime() {
		AtomicInteger running = new AtomicInteger();
		AtomicInteger maxRunning = new AtomicInteger();
		List<CompletableFuture<Void>> futures = new ArrayList<>();

		for (int i = 0; i < 8; i++) {
			futures.add(serializer.submit(() -> {
				maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
				sleep(10);
				running.decrementAndGet();
			}));
		}
		futures.forEach(CompletableFuture::join);

		assertThat(maxRunning.get()).isEqualTo(1);
	}

	@Test
	void shouldPreserveSubmissionOrder() {
		List<Integer> order = Collections.synchronizedList(new ArrayList<>());
		List<CompletableFuture<Void>> futures = new ArrayList<>();

		for (int i = 0; i < 10; i++) {
			int index = i;
			futures.add(serializer.submit(() -> order.add(index)));
		}
		futures.forEach(CompletableFuture::join);

		assertThat(order).containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
	}

	@Test
	void shouldKeepRunningAfterATaskFails() {
		CompletableFuture<Void> failing = serializer.submit(() -> {
			throw new IllegalStateException("boom");
		});
		AtomicInteger counter = new AtomicInteger();

		serializer.submit(counter::incrementAndGet).join();

		assertThat(failing).isCompletedExceptionally();
		assertThat(counter.get()).isEqualTo(1);
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

}
