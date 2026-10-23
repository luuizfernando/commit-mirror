package com.luiz.commit_mirror.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.luiz.commit_mirror.TestcontainersConfiguration;
import com.luiz.commit_mirror.domain.CommitLog;
import com.luiz.commit_mirror.repository.CommitLogRepository;

@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@SpringBootTest
class CommitLogServiceIT {

	private static final int CONCURRENT_COMMITS = 10;

	@Autowired
	private CommitLogService service;

	@Autowired
	private CommitLogRepository repository;

	@BeforeEach
	void cleanTable() {
		repository.deleteAll();
	}

	private List<CommitLog> registerConcurrently() {
		ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_COMMITS);
		try {
			List<CompletableFuture<CommitLog>> futures = IntStream.rangeClosed(1, CONCURRENT_COMMITS)
				.mapToObj(i -> CompletableFuture.supplyAsync(() -> service.register(i, 100L, "x").orElseThrow(), pool))
				.toList();
			return futures.stream().map(CompletableFuture::join).toList();
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	void shouldGenerateDistinctConsecutiveSequencesUnderConcurrentCommits() {
		List<Long> sequences = registerConcurrently().stream().map(CommitLog::getSequenceNumber).sorted().toList();

		assertThat(sequences).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
	}

	@Test
	void shouldPersistOneRowPerConcurrentCommit() {
		registerConcurrently();

		assertThat(repository.count()).isEqualTo(CONCURRENT_COMMITS);
	}

	@Test
	void shouldReturnEmptyWhenUpdateIdIsRegisteredTwice() {
		service.register(1L, 100L, "x");

		assertThat(service.register(1L, 100L, "x")).isEmpty();
	}

	@Test
	void shouldPersistOnlyOneRowWhenUpdateIdIsRegisteredTwice() {
		service.register(1L, 100L, "x");

		service.register(1L, 100L, "x");

		assertThat(repository.count()).isEqualTo(1L);
	}

}
