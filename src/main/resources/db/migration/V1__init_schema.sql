CREATE TABLE user_account (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'Primary key ID',
  username VARCHAR(64) NOT NULL COMMENT 'Username',
  password_hash VARCHAR(255) NOT NULL COMMENT 'Password hash',
  display_name VARCHAR(64) NOT NULL COMMENT 'Display name',
  phone VARCHAR(32) NULL COMMENT 'Phone number',
  email VARCHAR(128) NULL COMMENT 'Email address',
  status VARCHAR(32) NOT NULL DEFAULT 'ENABLED' COMMENT 'Account status',
  last_login_at DATETIME NULL COMMENT 'Last login time',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created time',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated time',
  created_by BIGINT NULL COMMENT 'Created by user ID',
  updated_by BIGINT NULL COMMENT 'Updated by user ID',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT 'Logical delete flag: 0 active, 1 deleted',
  UNIQUE KEY uk_user_account_username (username),
  KEY idx_user_account_status (status),
  KEY idx_user_account_deleted (deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='User account table';

CREATE TABLE role (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'Primary key ID',
  role_code VARCHAR(64) NOT NULL COMMENT 'Role code',
  role_name VARCHAR(64) NOT NULL COMMENT 'Role name',
  description VARCHAR(255) NULL COMMENT 'Role description',
  status VARCHAR(32) NOT NULL DEFAULT 'ENABLED' COMMENT 'Role status',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created time',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated time',
  created_by BIGINT NULL COMMENT 'Created by user ID',
  updated_by BIGINT NULL COMMENT 'Updated by user ID',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT 'Logical delete flag',
  UNIQUE KEY uk_role_code (role_code),
  KEY idx_role_deleted (deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Role table';

CREATE TABLE permission (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'Primary key ID',
  permission_code VARCHAR(128) NOT NULL COMMENT 'Permission code',
  permission_name VARCHAR(128) NOT NULL COMMENT 'Permission name',
  permission_type VARCHAR(32) NOT NULL COMMENT 'Permission type',
  parent_id BIGINT NULL COMMENT 'Parent permission ID',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created time',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated time',
  created_by BIGINT NULL COMMENT 'Created by user ID',
  updated_by BIGINT NULL COMMENT 'Updated by user ID',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT 'Logical delete flag',
  UNIQUE KEY uk_permission_code (permission_code),
  KEY idx_permission_parent (parent_id),
  KEY idx_permission_deleted (deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Permission table';

CREATE TABLE user_role (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'Primary key ID',
  user_id BIGINT NOT NULL COMMENT 'User ID',
  role_id BIGINT NOT NULL COMMENT 'Role ID',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created time',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated time',
  created_by BIGINT NULL COMMENT 'Created by user ID',
  updated_by BIGINT NULL COMMENT 'Updated by user ID',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT 'Logical delete flag',
  UNIQUE KEY uk_user_role (user_id, role_id, deleted),
  KEY idx_user_role_user (user_id),
  KEY idx_user_role_role (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='User role relation table';

CREATE TABLE role_permission (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'Primary key ID',
  role_id BIGINT NOT NULL COMMENT 'Role ID',
  permission_id BIGINT NOT NULL COMMENT 'Permission ID',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created time',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated time',
  created_by BIGINT NULL COMMENT 'Created by user ID',
  updated_by BIGINT NULL COMMENT 'Updated by user ID',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT 'Logical delete flag',
  UNIQUE KEY uk_role_permission (role_id, permission_id, deleted),
  KEY idx_role_permission_role (role_id),
  KEY idx_role_permission_permission (permission_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Role permission relation table';

CREATE TABLE project (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'Primary key ID',
  project_name VARCHAR(128) NOT NULL COMMENT 'Project name',
  project_code VARCHAR(64) NOT NULL COMMENT 'Project code',
  location VARCHAR(255) NULL COMMENT 'Project location',
  status VARCHAR(32) NOT NULL DEFAULT 'ENABLED' COMMENT 'Project status',
  description VARCHAR(500) NULL COMMENT 'Project description',
  settings JSON NULL COMMENT 'Project settings',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created time',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated time',
  created_by BIGINT NULL COMMENT 'Created by user ID',
  updated_by BIGINT NULL COMMENT 'Updated by user ID',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT 'Logical delete flag',
  UNIQUE KEY uk_project_code (project_code),
  KEY idx_project_status (status),
  KEY idx_project_deleted (deleted),
  KEY idx_project_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Project table';

CREATE TABLE project_member (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'Primary key ID',
  project_id BIGINT NOT NULL COMMENT 'Project ID',
  user_id BIGINT NOT NULL COMMENT 'User ID',
  project_role VARCHAR(64) NOT NULL COMMENT 'Project role',
  status VARCHAR(32) NOT NULL DEFAULT 'ENABLED' COMMENT 'Member status',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created time',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated time',
  created_by BIGINT NULL COMMENT 'Created by user ID',
  updated_by BIGINT NULL COMMENT 'Updated by user ID',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT 'Logical delete flag',
  UNIQUE KEY uk_project_member (project_id, user_id, deleted),
  KEY idx_project_member_project (project_id),
  KEY idx_project_member_user (user_id),
  KEY idx_project_member_deleted (deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Project member table';

CREATE TABLE file_object (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'Primary key ID',
  project_id BIGINT NOT NULL COMMENT 'Project ID',
  biz_type VARCHAR(64) NOT NULL COMMENT 'Business type',
  biz_id BIGINT NULL COMMENT 'Business ID',
  file_name VARCHAR(255) NOT NULL COMMENT 'File name',
  object_name VARCHAR(500) NOT NULL COMMENT 'Object storage name',
  content_type VARCHAR(128) NULL COMMENT 'Content type',
  file_size BIGINT NOT NULL DEFAULT 0 COMMENT 'File size',
  file_hash VARCHAR(128) NULL COMMENT 'File hash',
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT 'File status',
  metadata JSON NULL COMMENT 'Extra metadata',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created time',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated time',
  created_by BIGINT NULL COMMENT 'Created by user ID',
  updated_by BIGINT NULL COMMENT 'Updated by user ID',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT 'Logical delete flag',
  KEY idx_file_project (project_id),
  KEY idx_file_biz (biz_type, biz_id),
  KEY idx_file_status (status),
  KEY idx_file_deleted (deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='File object table';

CREATE TABLE knowledge_base (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'Primary key ID',
  project_id BIGINT NOT NULL COMMENT 'Project ID',
  name VARCHAR(128) NOT NULL COMMENT 'Knowledge base name',
  domain VARCHAR(64) NULL COMMENT 'Knowledge domain',
  status VARCHAR(32) NOT NULL DEFAULT 'ENABLED' COMMENT 'Knowledge base status',
  description VARCHAR(500) NULL COMMENT 'Knowledge base description',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created time',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated time',
  created_by BIGINT NULL COMMENT 'Created by user ID',
  updated_by BIGINT NULL COMMENT 'Updated by user ID',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT 'Logical delete flag',
  KEY idx_kb_project (project_id),
  KEY idx_kb_status (status),
  KEY idx_kb_deleted (deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Knowledge base table';

CREATE TABLE knowledge_document (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'Primary key ID',
  project_id BIGINT NOT NULL COMMENT 'Project ID',
  knowledge_base_id BIGINT NOT NULL COMMENT 'Knowledge base ID',
  file_id BIGINT NULL COMMENT 'File ID',
  title VARCHAR(255) NOT NULL COMMENT 'Document title',
  source_type VARCHAR(64) NULL COMMENT 'Source type',
  index_status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'Index status',
  version_no INT NOT NULL DEFAULT 1 COMMENT 'Version number',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created time',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated time',
  created_by BIGINT NULL COMMENT 'Created by user ID',
  updated_by BIGINT NULL COMMENT 'Updated by user ID',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT 'Logical delete flag',
  KEY idx_kdoc_project (project_id),
  KEY idx_kdoc_kb (knowledge_base_id),
  KEY idx_kdoc_status (index_status),
  KEY idx_kdoc_deleted (deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Knowledge document table';

CREATE TABLE data_source (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'Primary key ID',
  project_id BIGINT NOT NULL COMMENT 'Project ID',
  name VARCHAR(128) NOT NULL COMMENT 'Data source name',
  db_type VARCHAR(32) NOT NULL COMMENT 'Database type',
  jdbc_url VARCHAR(500) NOT NULL COMMENT 'JDBC URL',
  username VARCHAR(128) NOT NULL COMMENT 'Username',
  password_cipher VARCHAR(500) NOT NULL COMMENT 'Encrypted password',
  status VARCHAR(32) NOT NULL DEFAULT 'ENABLED' COMMENT 'Data source status',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created time',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated time',
  created_by BIGINT NULL COMMENT 'Created by user ID',
  updated_by BIGINT NULL COMMENT 'Updated by user ID',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT 'Logical delete flag',
  KEY idx_ds_project (project_id),
  KEY idx_ds_status (status),
  KEY idx_ds_deleted (deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Business data source table';

CREATE TABLE generate_task (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'Primary key ID',
  project_id BIGINT NOT NULL COMMENT 'Project ID',
  task_type VARCHAR(64) NOT NULL COMMENT 'Task type',
  biz_type VARCHAR(64) NULL COMMENT 'Business type',
  biz_id BIGINT NULL COMMENT 'Business ID',
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'Task status',
  current_stage VARCHAR(64) NULL COMMENT 'Current stage',
  retry_count INT NOT NULL DEFAULT 0 COMMENT 'Retry count',
  max_retry_count INT NOT NULL DEFAULT 3 COMMENT 'Max retry count',
  error_message TEXT NULL COMMENT 'Error message',
  started_at DATETIME NULL COMMENT 'Start time',
  finished_at DATETIME NULL COMMENT 'Finish time',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created time',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated time',
  created_by BIGINT NULL COMMENT 'Created by user ID',
  updated_by BIGINT NULL COMMENT 'Updated by user ID',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT 'Logical delete flag',
  KEY idx_task_project (project_id),
  KEY idx_task_status (status),
  KEY idx_task_biz (biz_type, biz_id),
  KEY idx_task_created_at (created_at),
  KEY idx_task_deleted (deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Generate task table';

CREATE TABLE task_stage_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'Primary key ID',
  project_id BIGINT NOT NULL COMMENT 'Project ID',
  task_id BIGINT NOT NULL COMMENT 'Task ID',
  stage_code VARCHAR(64) NOT NULL COMMENT 'Stage code',
  status VARCHAR(32) NOT NULL COMMENT 'Stage status',
  input_summary TEXT NULL COMMENT 'Input summary',
  output_summary TEXT NULL COMMENT 'Output summary',
  error_message TEXT NULL COMMENT 'Error message',
  started_at DATETIME NULL COMMENT 'Start time',
  finished_at DATETIME NULL COMMENT 'Finish time',
  cost_ms BIGINT NULL COMMENT 'Cost in milliseconds',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created time',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated time',
  created_by BIGINT NULL COMMENT 'Created by user ID',
  updated_by BIGINT NULL COMMENT 'Updated by user ID',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT 'Logical delete flag',
  KEY idx_stage_project (project_id),
  KEY idx_stage_task (task_id),
  KEY idx_stage_code (stage_code),
  KEY idx_stage_deleted (deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Task stage log table';

CREATE TABLE audit_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'Primary key ID',
  project_id BIGINT NULL COMMENT 'Project ID',
  operator_id BIGINT NULL COMMENT 'Operator user ID',
  action VARCHAR(64) NOT NULL COMMENT 'Action',
  object_type VARCHAR(64) NOT NULL COMMENT 'Object type',
  object_id BIGINT NULL COMMENT 'Object ID',
  request_id VARCHAR(64) NULL COMMENT 'Request ID',
  ip_address VARCHAR(64) NULL COMMENT 'IP address',
  detail JSON NULL COMMENT 'Detail',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created time',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated time',
  created_by BIGINT NULL COMMENT 'Created by user ID',
  updated_by BIGINT NULL COMMENT 'Updated by user ID',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT 'Logical delete flag',
  KEY idx_audit_project (project_id),
  KEY idx_audit_operator (operator_id),
  KEY idx_audit_action (action),
  KEY idx_audit_created_at (created_at),
  KEY idx_audit_deleted (deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Audit log table';

CREATE TABLE system_config (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'Primary key ID',
  config_key VARCHAR(128) NOT NULL COMMENT 'Config key',
  config_value TEXT NULL COMMENT 'Config value',
  config_group VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT 'Config group',
  description VARCHAR(255) NULL COMMENT 'Description',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created time',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated time',
  created_by BIGINT NULL COMMENT 'Created by user ID',
  updated_by BIGINT NULL COMMENT 'Updated by user ID',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT 'Logical delete flag',
  UNIQUE KEY uk_system_config_key (config_key),
  KEY idx_system_config_group (config_group),
  KEY idx_system_config_deleted (deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='System config table';

CREATE TABLE external_call_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'Primary key ID',
  project_id BIGINT NULL COMMENT 'Project ID',
  service_name VARCHAR(128) NOT NULL COMMENT 'External service name',
  call_type VARCHAR(64) NOT NULL COMMENT 'Call type',
  request_id VARCHAR(64) NULL COMMENT 'Request ID',
  request_summary TEXT NULL COMMENT 'Request summary',
  response_summary TEXT NULL COMMENT 'Response summary',
  status VARCHAR(32) NOT NULL COMMENT 'Call status',
  cost_ms BIGINT NULL COMMENT 'Cost in milliseconds',
  error_message TEXT NULL COMMENT 'Error message',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created time',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated time',
  created_by BIGINT NULL COMMENT 'Created by user ID',
  updated_by BIGINT NULL COMMENT 'Updated by user ID',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT 'Logical delete flag',
  KEY idx_external_project (project_id),
  KEY idx_external_service (service_name),
  KEY idx_external_status (status),
  KEY idx_external_created_at (created_at),
  KEY idx_external_deleted (deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='External call log table';

INSERT INTO user_account (id, username, password_hash, display_name, status, created_by, updated_by)
VALUES (1, 'admin', '$2a$10$7EqJtq98hPqEX7fNZaFWoOHiIkXM6DgNc4f/cgPLylMVO7YLqFq2W', 'System Administrator', 'ENABLED', 1, 1);

INSERT INTO role (id, role_code, role_name, description, status, created_by, updated_by)
VALUES (1, 'PLATFORM_ADMIN', 'Platform Administrator', 'Platform global administrator role', 'ENABLED', 1, 1),
       (2, 'PROJECT_ADMIN', 'Project Administrator', 'Project administrator role', 'ENABLED', 1, 1),
       (3, 'BUSINESS_USER', 'Business User', 'Normal business operation role', 'ENABLED', 1, 1),
       (4, 'VIEWER', 'Viewer', 'Read-only viewer role', 'ENABLED', 1, 1);

INSERT INTO permission (id, permission_code, permission_name, permission_type, created_by, updated_by)
VALUES (1, 'system:manage', 'System Management', 'MENU', 1, 1),
       (2, 'project:view', 'Project View', 'API', 1, 1),
       (3, 'project:manage', 'Project Management', 'API', 1, 1),
       (4, 'file:manage', 'File Management', 'API', 1, 1);

INSERT INTO user_role (user_id, role_id, created_by, updated_by)
VALUES (1, 1, 1, 1);

INSERT INTO role_permission (role_id, permission_id, created_by, updated_by)
VALUES (1, 1, 1, 1), (1, 2, 1, 1), (1, 3, 1, 1), (1, 4, 1, 1);

INSERT INTO project (id, project_name, project_code, location, status, description, created_by, updated_by)
VALUES (1, 'Demo Smart Worksite', 'DEMO-WORKSITE', 'Demo Location', 'ENABLED', 'Demo project for local development and integration testing', 1, 1);

INSERT INTO project_member (project_id, user_id, project_role, status, created_by, updated_by)
VALUES (1, 1, 'PROJECT_ADMIN', 'ENABLED', 1, 1);

INSERT INTO system_config (config_key, config_value, config_group, description, created_by, updated_by)
VALUES ('file.max-size', '52428800', 'file', 'Maximum upload file size', 1, 1),
       ('task.max-retry-count', '3', 'task', 'Maximum task retry count', 1, 1);
