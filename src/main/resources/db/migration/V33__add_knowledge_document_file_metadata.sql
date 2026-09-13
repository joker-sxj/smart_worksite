ALTER TABLE knowledge_document
  ADD COLUMN file_ext VARCHAR(32) NULL COMMENT 'Uploaded file extension' AFTER title,
  ADD COLUMN content_type VARCHAR(128) NULL COMMENT 'Uploaded file content type' AFTER file_ext;
