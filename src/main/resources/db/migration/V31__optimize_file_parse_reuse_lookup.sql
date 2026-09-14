ALTER TABLE file_parse_record
  ADD KEY idx_file_parse_reuse_lookup (
    project_id, file_id, source_file_hash, result_format, deleted, finished_at DESC, id DESC
  );
