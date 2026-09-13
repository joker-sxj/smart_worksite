ALTER TABLE review_record
  ADD COLUMN template_name VARCHAR(255) NULL AFTER template_id,
  ADD COLUMN template_version VARCHAR(64) NULL AFTER template_name;

UPDATE review_record r
JOIN template t ON t.id = r.template_id
SET r.template_name = t.template_name,
    r.template_version = t.version_no
WHERE r.template_name IS NULL;
