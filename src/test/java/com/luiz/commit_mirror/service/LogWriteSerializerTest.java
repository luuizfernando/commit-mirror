package com.luiz.commit_mirror.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.luiz.commit_mirror.support.LogCapture;

class LogWriteSerializerTest {

	private static final int TASKS = 10;

	private final LogWriteSerializer serializer = new LogWriteSerializer();

	private final LogCapture logs = LogCapture.attach(LogWriteSerializer.class);

	@AfterEach
	void shutdown() {
		serializer.shutdown();
		logs.detach();
	}

	private void submitAndWait(int count, Runnable task) {
		List<CompletableFuture<Void>> futures = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			futures.add(serializer.submit(task));
		}
		futures.forEach(CompletableFuture::join);
	}

	private CompletableFuture<Void> submitFailingTask() {
		return serializer.submit(() -> {
			throw new IllegalStateException("boom");
		});
	}

	@Test
	void shouldRunTheSubmittedTask() {
		AtomicInteger counter = new AtomicInteger();

		serializer.submit(counter::incrementAndGet).join();

		assertThat(counter.get()).isEqualTo(1);
	}

	@Test
	void shouldRunEveryTaskOnTheSameSingleWorkerThread() {
		Set<String> threads = ConcurrentHashMap.newKeySet();

		submitAndWait(TASKS, () -> threads.add(Thread.currentThread().getName()));

		assertThat(threads).containsExactly("log-write-worker");
	}

	@Test
	void shouldNeverRunTwoTasksAtTheSameTime() {
		AtomicInteger running = new AtomicInteger();
		AtomicInteger maxRunning = new AtomicInteger();

		submitAndWait(TASKS, () -> {
			maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
			running.decrementAndGet();
		});

		assertThat(maxRunning.get()).isEqualTo(1);
	}

	@Test
	void shouldPreserveSubmissionOrder() {
		List<Integer> order = Collections.synchronizedList(new ArrayList<>());
		List<CompletableFuture<Void>> futures = new ArrayList<>();

		for (int i = 0; i < TASKS; i++) {
			int index = i;
			futures.add(serializer.submit(() -> order.add(index)));
		}
		futures.forEach(CompletableFuture::join);

		assertThat(order).containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
	}

	@Test
	void shouldCompleteFutureExceptionallyWithTheTaskFailure() {
		CompletableFuture<Void> failing = submitFailingTask();

		assertThatThrownBy(failing::join).hasRootCauseInstanceOf(IllegalStateException.class)
				.hasRootCauseMessage("boom");
	}

	@Test
	void shouldKeepRunningAfterATaskFails() {
		submitFailingTask();
		AtomicInteger counter = new AtomicInteger();

		serializer.submit(counter::incrementAndGet).join();

		assertThat(counter.get()).isEqualTo(1);
	}

	@Test
	void shouldLogErrorWhenATaskFails() {
		submitFailingTask().exceptionally(ex -> null).join();

		assertThat(logs.hasErrorWithThrowableMessage("boom")).isTrue();
	}

	@Test
	void shouldRejectSubmissionsAfterShutdown() {
		serializer.shutdown();

		assertThatThrownBy(() -> serializer.submit(() -> {
		})).isInstanceOf(RejectedExecutionException.class);
	}

}
