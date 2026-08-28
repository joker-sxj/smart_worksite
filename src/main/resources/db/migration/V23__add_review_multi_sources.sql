ALTER TABLE review_record
  ADD COLUMN reference_file_ids JSON NULL COMMENT 'Snapshot of uploaded reference PDF file IDs' AFTER file_id,
  ADD COLUMN knowledge_base_ids JSON NULL COMMENT 'Snapshot of selected knowledge base IDs' AFTER reference_file_ids,
  ADD COLUMN references_json JSON NULL COMMENT 'Review citations and source references' AFTER result_json;
