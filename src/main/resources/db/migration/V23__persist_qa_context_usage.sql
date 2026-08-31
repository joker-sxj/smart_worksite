ALTER TABLE qa_message
  ADD COLUMN usage_json JSON NULL COMMENT 'Provider and context budget usage' AFTER references_json;
