ALTER TABLE ocr_record
  ADD COLUMN manually_confirmed TINYINT NOT NULL DEFAULT 0 AFTER error_message,
  ADD COLUMN confirmed_by BIGINT NULL AFTER manually_confirmed,
  ADD COLUMN confirmed_at DATETIME NULL AFTER confirmed_by;
