CREATE TABLE review_field_schema (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  project_id BIGINT NOT NULL,
  template_id BIGINT NOT NULL,
  version INT NOT NULL,
  fields_json JSON NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  created_by BIGINT NULL,
  updated_by BIGINT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_review_field_schema_version (project_id, template_id, version),
  KEY idx_review_field_schema_active (project_id, template_id, status, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Versioned review field schemas';

ALTER TABLE review_record
  ADD COLUMN field_schema_id BIGINT NULL AFTER template_id,
  ADD COLUMN field_schema_version INT NULL AFTER field_schema_id,
  ADD COLUMN input_fields_json JSON NULL AFTER result_json,
  ADD COLUMN document_fields_json JSON NULL AFTER input_fields_json,
  ADD COLUMN result_fields_json JSON NULL AFTER document_fields_json;
