ALTER TABLE qa_message
  ADD COLUMN resolved_question TEXT NULL,
  ADD COLUMN suggestions_json JSON NULL,
  ADD COLUMN suggestion_status VARCHAR(16) NULL,
  ADD COLUMN client_request_id VARCHAR(128) NULL,
  ADD COLUMN source_suggestion_message_id BIGINT NULL;

ALTER TABLE qa_message ADD UNIQUE KEY uk_qa_message_submit (session_id, created_by, client_request_id);

CREATE TABLE qa_session_memory (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_id BIGINT NOT NULL,
  project_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  summary_json JSON NOT NULL,
  covered_message_id BIGINT NULL,
  version INT NOT NULL DEFAULT 1,
  estimated_tokens INT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_qa_session_memory_scope (session_id, project_id, user_id),
  KEY idx_qa_session_memory_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
