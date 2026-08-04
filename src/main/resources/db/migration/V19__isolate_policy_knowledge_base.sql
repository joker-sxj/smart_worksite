ALTER TABLE knowledge_base
    ADD COLUMN knowledge_base_type VARCHAR(16) NOT NULL DEFAULT 'PROJECT' AFTER domain;

UPDATE knowledge_base
SET knowledge_base_type = 'PROJECT'
WHERE knowledge_base_type IS NULL OR knowledge_base_type = '';

CREATE INDEX idx_knowledge_base_project_type_status
    ON knowledge_base (project_id, knowledge_base_type, status, deleted);
