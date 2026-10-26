package com.luiz.commit_mirror.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.luiz.commit_mirror.domain.CommitLog;
import com.luiz.commit_mirror.domain.CommitStatus;

public interface CommitLogRepository extends JpaRepository<CommitLog, Long> {

	boolean existsByTelegramUpdateId(long telegramUpdateId);

	@Query("select coalesce(max(c.sequenceNumber), 0) from CommitLog c")
	long findMaxSequenceNumber();

	long countByStatus(CommitStatus status);

	List<CommitLog> findByStatusAndCreatedAtBeforeOrderBySequenceNumber(CommitStatus status, Instant createdBefore);

	List<CommitLog> findByStatusAndAttemptCountLessThanAndUpdatedAtBeforeOrderBySequenceNumber(CommitStatus status,
			int attemptCount, Instant updatedBefore);

}
