ALTER TABLE data_source
  ADD COLUMN field_whitelist_json TEXT NULL COMMENT 'JSON array of allowed selected field names for read-only database question validation'
  AFTER table_whitelist_json;
