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

	private static final Instant OLD = NOW.minusSeconds(3600);

	private static final Instant THRESHOLD = NOW.minusSeconds(300);

	private static final int MAX_ATTEMPTS = 2;

	@Autowired
	private CommitLogRepository repository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void cleanTable() {
		repository.deleteAll();
	}

	private static CommitLog log(long sequence, Instant createdAt) {
		return CommitLog.create(sequence, 100L, "typed", LocalDate.of(2026, 10, 21), sequence, createdAt);
	}

	private CommitLog savePending(long sequence, Instant createdAt) {
		return repository.saveAndFlush(log(sequence, createdAt));
	}

	private CommitLog saveFailed(long sequence, int failures, Instant updatedAt) {
		CommitLog log = log(sequence, OLD);
		log.markFailed(updatedAt);
		for (int i = 1; i < failures; i++) {
			log.reopen(updatedAt);
			log.markFailed(updatedAt);
		}
		return repository.saveAndFlush(log);
	}

	private CommitLog saveSucceeded(long sequence) {
		CommitLog log = log(sequence, OLD);
		log.markSuccess("sha", OLD);
		return repository.saveAndFlush(log);
	}

	private List<Long> stalePendingIds() {
		return repository.findByStatusAndCreatedAtBeforeOrderBySequenceNumber(CommitStatus.PENDING, THRESHOLD)
			.stream()
			.map(CommitLog::getId)
			.toList();
	}

	private List<Long> retryableFailedIds() {
		return repository
			.findByStatusAndAttemptCountLessThanAndUpdatedAtBeforeOrderBySequenceNumber(CommitStatus.FAILED,
					MAX_ATTEMPTS, THRESHOLD)
			.stream()
			.map(CommitLog::getId)
			.toList();
	}

	@Test
	void shouldApplyFlywayMigration() {
		Integer applied = jdbcTemplate.queryForObject(
				"select count(*) from flyway_schema_history where version = '1' and success", Integer.class);

		assertThat(applied).isEqualTo(1);
	}

	@Test
	void shouldPersistAndLoadEveryField() {
		CommitLog saved = savePending(1L, NOW);

		CommitLog loaded = repository.findById(saved.getId()).orElseThrow();

		assertThat(loaded).usingRecursiveComparison().isEqualTo(saved);
	}

	@Test
	void shouldRejectDuplicateTelegramUpdateId() {
		repository.saveAndFlush(CommitLog.create(1L, 100L, "typed", LocalDate.of(2026, 10, 21), 1L, NOW));

		assertThatThrownBy(
				() -> repository.saveAndFlush(CommitLog.create(1L, 100L, "typed", LocalDate.of(2026, 10, 21), 2L, NOW)))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void shouldRejectDuplicateSequenceNumber() {
		repository.saveAndFlush(CommitLog.create(1L, 100L, "typed", LocalDate.of(2026, 10, 21), 1L, NOW));

		assertThatThrownBy(
				() -> repository.saveAndFlush(CommitLog.create(2L, 100L, "typed", LocalDate.of(2026, 10, 21), 1L, NOW)))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void shouldReturnZeroMaxSequenceWhenThereAreNoCommits() {
		assertThat(repository.findMaxSequenceNumber()).isZero();
	}

	@Test
	void shouldReturnMaxSequenceGlobally() {
		savePending(1L, NOW);
		savePending(2L, NOW);

		assertThat(repository.findMaxSequenceNumber()).isEqualTo(2L);
	}

	@Test
	void shouldReportExistingUpdateId() {
		savePending(1L, NOW);

		assertThat(repository.existsByTelegramUpdateId(1L)).isTrue();
	}

	@Test
	void shouldReportMissingUpdateId() {
		savePending(1L, NOW);

		assertThat(repository.existsByTelegramUpdateId(2L)).isFalse();
	}

	@Test
	void shouldFindPendingCreatedBeforeThreshold() {
		CommitLog stale = savePending(1L, OLD);

		assertThat(stalePendingIds()).containsExactly(stale.getId());
	}

	@Test
	void shouldNotFindPendingCreatedAfterThreshold() {
		savePending(1L, NOW);

		assertThat(stalePendingIds()).isEmpty();
	}

	@Test
	void shouldNotFindPendingCreatedExactlyAtThreshold() {
		savePending(1L, THRESHOLD);

		assertThat(stalePendingIds()).isEmpty();
	}

	@Test
	void shouldNotFindOtherStatusesAsPending() {
		saveFailed(1L, 1, OLD);
		saveSucceeded(2L);

		assertThat(stalePendingIds()).isEmpty();
	}

	@Test
	void shouldOrderStalePendingBySequence() {
		CommitLog second = savePending(2L, OLD);
		CommitLog first = savePending(1L, OLD);

		assertThat(stalePendingIds()).containsExactly(first.getId(), second.getId());
	}

	@Test
	void shouldFindFailedWithAttemptsLeftUpdatedBeforeThreshold() {
		CommitLog failed = saveFailed(1L, 1, OLD);

		assertThat(retryableFailedIds()).containsExactly(failed.getId());
	}

	@Test
	void shouldNotFindFailedThatExhaustedItsAttempts() {
		saveFailed(1L, MAX_ATTEMPTS, OLD);

		assertThat(retryableFailedIds()).isEmpty();
	}

	@Test
	void shouldNotFindFailedUpdatedAfterThreshold() {
		saveFailed(1L, 1, NOW);

		assertThat(retryableFailedIds()).isEmpty();
	}

	@Test
	void shouldNotFindOtherStatusesAsRetryable() {
		savePending(1L, OLD);
		saveSucceeded(2L);

		assertThat(retryableFailedIds()).isEmpty();
	}

	@Test
	void shouldOrderRetryableFailedBySequence() {
		CommitLog second = saveFailed(2L, 1, OLD);
		CommitLog first = saveFailed(1L, 1, OLD);

		assertThat(retryableFailedIds()).containsExactly(first.getId(), second.getId());
	}

	@Test
	void shouldCountZeroWhenTableIsEmpty() {
		assertThat(repository.countByStatus(CommitStatus.SUCCESS)).isZero();
	}

	@Test
	void shouldCountOnlyRecordsWithTheRequestedStatus() {
		saveSucceeded(1L);
		saveSucceeded(2L);
		savePending(3L, NOW);
		saveFailed(4L, 1, OLD);

		assertThat(repository.countByStatus(CommitStatus.SUCCESS)).isEqualTo(2L);
	}

}
