package com.luiz.commit_mirror.domain;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "commit_log")
public class CommitLog {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "telegram_update_id", nullable = false, updatable = false)
	private long telegramUpdateId;

	@Column(name = "chat_id", nullable = false, updatable = false)
	private long chatId;

	@Column(nullable = false, updatable = false)
	private String message;

	@Column(name = "commit_date", nullable = false, updatable = false)
	private LocalDate commitDate;

	@Column(name = "sequence_number", nullable = false, updatable = false)
	private long sequenceNumber;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private CommitStatus status;

	@Column(name = "github_commit_sha")
	private String githubCommitSha;

	@Column(name = "attempt_count", nullable = false)
	private int attemptCount;

	protected CommitLog() {
	}

	private CommitLog(long telegramUpdateId, long chatId, String message, LocalDate commitDate, long sequenceNumber,
			Instant now) {
		this.telegramUpdateId = telegramUpdateId;
		this.chatId = chatId;
		this.message = message;
		this.commitDate = commitDate;
		this.sequenceNumber = sequenceNumber;
		this.createdAt = now;
		this.updatedAt = now;
		this.status = CommitStatus.PENDING;
	}

	public static CommitLog create(long telegramUpdateId, long chatId, String message, LocalDate commitDate,
			long sequenceNumber, Instant now) {
		return new CommitLog(telegramUpdateId, chatId, message, commitDate, sequenceNumber, now);
	}

	public void markSuccess(String commitSha, Instant now) {
		requireStatus(CommitStatus.PENDING, "mark as success");
		this.status = CommitStatus.SUCCESS;
		this.githubCommitSha = commitSha;
		this.updatedAt = now;
	}

	public void markFailed(Instant now) {
		requireStatus(CommitStatus.PENDING, "mark as failed");
		this.status = CommitStatus.FAILED;
		this.attemptCount++;
		this.updatedAt = now;
	}

	public void reopen(Instant now) {
		requireStatus(CommitStatus.FAILED, "reopen");
		this.status = CommitStatus.PENDING;
		this.updatedAt = now;
	}

	private void requireStatus(CommitStatus expected, String action) {
		if (status != expected) {
			throw new IllegalStateException("Cannot " + action + " a commit log in status " + status);
		}
	}

	public Long getId() {
		return id;
	}

	public long getTelegramUpdateId() {
		return telegramUpdateId;
	}

	public long getChatId() {
		return chatId;
	}

	public String getMessage() {
		return message;
	}

	public LocalDate getCommitDate() {
		return commitDate;
	}

	public long getSequenceNumber() {
		return sequenceNumber;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public CommitStatus getStatus() {
		return status;
	}

	public String getGithubCommitSha() {
		return githubCommitSha;
	}

	public int getAttemptCount() {
		return attemptCount;
	}

}
