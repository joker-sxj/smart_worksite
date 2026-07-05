CREATE TABLE qa_session (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'Q&A session ID',
  project_id BIGINT NOT NULL COMMENT 'Project ID',
  title VARCHAR(100) NULL COMMENT 'Session title',
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT 'Session status',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created time',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated time',
  created_by BIGINT NULL COMMENT 'Created by user ID',
  updated_by BIGINT NULL COMMENT 'Updated by user ID',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT 'Logical delete flag',
  KEY idx_qa_session_project (project_id),
  KEY idx_qa_session_creator (created_by),
  KEY idx_qa_session_deleted (deleted)
) COMMENT='Q&A conversation session';

CREATE TABLE qa_message (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'Q&A message ID',
  project_id BIGINT NOT NULL COMMENT 'Project ID',
  session_id BIGINT NOT NULL COMMENT 'Q&A session ID',
  user_id BIGINT NULL COMMENT 'Caller user ID',
  role VARCHAR(32) NOT NULL COMMENT 'Message role',
  content TEXT NULL COMMENT 'User or assistant display content',
  reply_status VARCHAR(32) NULL COMMENT 'Assistant reply status',
  route_mode VARCHAR(32) NULL COMMENT 'Selected route mode',
  route_rationale VARCHAR(1000) NULL COMMENT 'Route rationale',
  answer_content TEXT NULL COMMENT 'Validated answer content when available',
  pending_reason VARCHAR(500) NULL COMMENT 'Reason answer synthesis is pending',
  clarification_question VARCHAR(1000) NULL COMMENT 'Clarification question',
  request_id VARCHAR(128) NULL COMMENT 'Request correlation ID',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created time',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated time',
  created_by BIGINT NULL COMMENT 'Created by user ID',
  updated_by BIGINT NULL COMMENT 'Updated by user ID',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT 'Logical delete flag',
  KEY idx_qa_message_session (session_id),
  KEY idx_qa_message_project (project_id),
  KEY idx_qa_message_request (request_id),
  KEY idx_qa_message_deleted (deleted)
) COMMENT='Q&A conversation message';
