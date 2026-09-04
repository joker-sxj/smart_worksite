CREATE TABLE ocr_field_revision (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  project_id BIGINT NOT NULL,
  record_id BIGINT NOT NULL,
  field_key VARCHAR(128) NOT NULL,
  old_value TEXT NULL,
  new_value TEXT NULL,
  revised_by BIGINT NULL,
  revised_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  created_by BIGINT NULL,
  updated_by BIGINT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  KEY idx_ocr_revision_record (record_id),
  KEY idx_ocr_revision_project (project_id),
  KEY idx_ocr_revision_field (field_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='OCR field revision history';
