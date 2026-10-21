package com.luiz.commit_mirror.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.luiz.commit_mirror.TestcontainersConfiguration;
import com.luiz.commit_mirror.domain.CommitLog;
import com.luiz.commit_mirror.domain.CommitStatus;

@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@SpringBootTest
class CommitLogRepositoryIT {

	private static final Instant NOW = Instant.parse("2026-10-21T15:00:00Z");

	@Autowired
	private CommitLogRepository repository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void cleanTable() {
		repository.deleteAll();
	}

	private CommitLog log(long updateId, long sequence, Instant createdAt) {
		return CommitLog.create(updateId, 100L, "typed", LocalDate.of(2026, 10, 21), sequence, createdAt);
	}

	@Test
	void shouldApplyFlywayMigration() {
		Integer applied = jdbcTemplate.queryForObject(
				"select count(*) from flyway_schema_history where version = '1' and success", Integer.class);

		assertThat(applied).isEqualTo(1);
	}

	@Test
	void shouldPersistAndLoadCommitLog() {
		CommitLog saved = repository.saveAndFlush(log(1L, 1L, NOW));

		CommitLog loaded = repository.findById(saved.getId()).orElseThrow();

		assertThat(loaded.getTelegramUpdateId()).isEqualTo(1L);
		assertThat(loaded.getChatId()).isEqualTo(100L);
		assertThat(loaded.getMessage()).isEqualTo("typed");
		assertThat(loaded.getCommitDate()).isEqualTo(LocalDate.of(2026, 10, 21));
		assertThat(loaded.getStatus()).isEqualTo(CommitStatus.PENDING);
		assertThat(loaded.getCreatedAt()).isEqualTo(NOW);
	}

	@Test
	void shouldRejectDuplicateTelegramUpdateId() {
		repository.saveAndFlush(log(1L, 1L, NOW));

		assertThatThrownBy(() -> repository.saveAndFlush(log(1L, 2L, NOW)))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void shouldRejectDuplicateSequenceNumber() {
		repository.saveAndFlush(log(1L, 1L, NOW));

		assertThatThrownBy(() -> repository.saveAndFlush(log(2L, 1L, NOW)))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void shouldReturnZeroWhenThereAreNoCommits() {
		assertThat(repository.findMaxSequenceNumber()).isZero();
	}

	@Test
	void shouldReturnMaxSequenceGlobally() {
		repository.saveAndFlush(log(1L, 1L, NOW));
		repository.saveAndFlush(log(2L, 2L, NOW));

		assertThat(repository.findMaxSequenceNumber()).isEqualTo(2L);
	}

	@Test
	void shouldReportExistingUpdateId() {
		repository.saveAndFlush(log(1L, 1L, NOW));

		assertThat(repository.existsByTelegramUpdateId(1L)).isTrue();
		assertThat(repository.existsByTelegramUpdateId(2L)).isFalse();
	}

	@Test
	void shouldFindFailedAndStalePendingRecords() {
		Instant old = NOW.minusSeconds(3600);
		CommitLog stalePending = repository.saveAndFlush(log(1L, 1L, old));
		repository.saveAndFlush(log(2L, 2L, NOW));
		CommitLog failed = log(3L, 3L, old);
		failed.markFailed(old);
		repository.saveAndFlush(failed);
		CommitLog exhausted = log(4L, 4L, old);
		exhausted.markFailed(old);
		exhausted.reopen(old);
		exhausted.markFailed(old);
		repository.saveAndFlush(exhausted);
		CommitLog done = log(5L, 5L, old);
		done.markSuccess("sha", old);
		repository.saveAndFlush(done);

		Instant threshold = NOW.minusSeconds(300);
		List<CommitLog> pending = repository
			.findByStatusAndCreatedAtBeforeOrderBySequenceNumber(CommitStatus.PENDING, threshold);
		List<CommitLog> retryable = repository
			.findByStatusAndAttemptCountLessThanAndUpdatedAtBeforeOrderBySequenceNumber(CommitStatus.FAILED, 2,
					threshold);

		assertThat(pending).extracting(CommitLog::getId).containsExactly(stalePending.getId());
		assertThat(retryable).extracting(CommitLog::getId).containsExactly(failed.getId());
	}

}
