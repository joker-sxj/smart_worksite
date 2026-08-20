UPDATE file_parse_record target
JOIN (
  SELECT id
  FROM (
    SELECT id,
           ROW_NUMBER() OVER (
             PARTITION BY project_id, file_id, IF(source_file_hash IS NULL, 'N:', CONCAT('V:', source_file_hash)), result_format
             ORDER BY created_at DESC, id DESC
           ) AS duplicate_rank
    FROM file_parse_record
    WHERE deleted = 0
      AND status IN ('PENDING', 'PARSING', 'RUNNING')
  ) ranked
  WHERE duplicate_rank > 1
) duplicates ON duplicates.id = target.id
SET target.status = 'FAILED',
    target.current_stage = 'FAILED',
    target.error_message = 'superseded duplicate active parse task during idempotency migration',
    target.finished_at = CURRENT_TIMESTAMP,
    target.updated_at = CURRENT_TIMESTAMP;

ALTER TABLE file_parse_record
  ADD COLUMN active_identity VARCHAR(256)
    GENERATED ALWAYS AS (
      CASE
        WHEN deleted = 0 AND status IN ('PENDING', 'PARSING', 'RUNNING')
          THEN CONCAT(project_id, ':', file_id, ':',
            IF(source_file_hash IS NULL, 'N:', CONCAT('V:', source_file_hash)), ':', result_format)
        ELSE NULL
      END
    ) STORED COMMENT 'Generated identity for one active parse per source and format',
  ADD UNIQUE KEY uk_file_parse_active_identity (active_identity);
