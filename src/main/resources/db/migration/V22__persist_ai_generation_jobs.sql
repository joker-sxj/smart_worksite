ALTER TABLE qa_message
  ADD COLUMN request_json JSON NULL COMMENT 'Persisted generation request' AFTER task_id,
  ADD COLUMN error_message TEXT NULL COMMENT 'Generation error message' AFTER request_json;

ALTER TABLE qa_message
  MODIFY COLUMN status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'Message status: PENDING, PROCESSING, SUCCESS, FAILED';

ALTER TABLE qa_message
  ADD KEY idx_qa_message_task (task_id);

ALTER TABLE review_record
  ADD KEY idx_review_record_task (task_id);
