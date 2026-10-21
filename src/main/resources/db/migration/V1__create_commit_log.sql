CREATE TABLE commit_log (
  id                 BIGSERIAL    PRIMARY KEY,
  telegram_update_id BIGINT       NOT NULL,
  chat_id            BIGINT       NOT NULL,
  message            TEXT         NOT NULL,
  commit_date        DATE         NOT NULL,
  sequence_number    BIGINT       NOT NULL CHECK (sequence_number > 0),
  created_at         TIMESTAMPTZ  NOT NULL,
  updated_at         TIMESTAMPTZ  NOT NULL,
  status             VARCHAR(16)  NOT NULL CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED')),
  github_commit_sha  VARCHAR(64),
  attempt_count      INTEGER      NOT NULL DEFAULT 0,
  CONSTRAINT uq_commit_log_update_id UNIQUE (telegram_update_id),
  CONSTRAINT uq_commit_log_sequence_number UNIQUE (sequence_number)
);

CREATE INDEX idx_commit_log_status ON commit_log (status, created_at);
