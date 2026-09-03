CREATE TABLE review_reference (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  review_record_id BIGINT NOT NULL,
  project_id BIGINT NOT NULL,
  reference_type VARCHAR(32) NOT NULL,
  document_id BIGINT NULL,
  file_id BIGINT NULL,
  source_name VARCHAR(500) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT chk_review_reference_source CHECK (
    (reference_type = 'KNOWLEDGE_DOCUMENT' AND document_id IS NOT NULL AND file_id IS NULL)
    OR (reference_type = 'TEMPORARY_FILE' AND file_id IS NOT NULL AND document_id IS NULL)
  ),
  UNIQUE KEY uk_review_reference_document (review_record_id, reference_type, document_id),
  UNIQUE KEY uk_review_reference_file (review_record_id, reference_type, file_id),
  KEY idx_review_reference_project (project_id),
  KEY idx_review_reference_record (review_record_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Review reference sources';

CREATE TABLE review_rule_result (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  review_record_id BIGINT NOT NULL,
  project_id BIGINT NOT NULL,
  rule_id VARCHAR(128) NOT NULL,
  status VARCHAR(32) NOT NULL,
  result_json JSON NOT NULL,
  confidence DECIMAL(5,4) NULL,
  manual_confirmation_required TINYINT NOT NULL DEFAULT 0,
  error_message VARCHAR(1000) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_review_rule_result (review_record_id, rule_id),
  KEY idx_review_rule_project (project_id),
  KEY idx_review_rule_status (review_record_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Rule-level compliance review results';
