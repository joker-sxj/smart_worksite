ALTER TABLE file_parse_record
  ADD KEY idx_file_parse_latest (
    project_id, file_id, deleted, created_at DESC, id DESC
  ),
  ADD KEY idx_file_parse_latest_success (
    project_id, file_id, deleted, finished_at DESC, id DESC
  );
