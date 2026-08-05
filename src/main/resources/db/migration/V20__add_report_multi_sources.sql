ALTER TABLE template_variable_description
  ADD COLUMN data_source_ids JSON NULL COMMENT 'Optional data source whitelist for this variable' AFTER description;

ALTER TABLE report_variable_value
  ADD COLUMN knowledge_base_ids JSON NULL COMMENT 'Selected knowledge base IDs snapshot' AFTER knowledge_base_id,
  ADD COLUMN data_source_ids JSON NULL COMMENT 'Selected data source IDs snapshot' AFTER knowledge_base_ids;

UPDATE report_variable_value
SET knowledge_base_ids = JSON_ARRAY(knowledge_base_id),
    data_source_ids = JSON_ARRAY()
WHERE knowledge_base_ids IS NULL;
