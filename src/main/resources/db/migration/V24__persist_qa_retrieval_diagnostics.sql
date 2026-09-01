ALTER TABLE qa_message
  ADD COLUMN retrieval_diagnostics_json JSON NULL COMMENT 'Safe QA retrieval diagnostics' AFTER usage_json;
